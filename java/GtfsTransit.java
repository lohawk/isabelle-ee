import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.Route;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.gtfs.model.Trip;
import com.conveyal.gtfs.model.StopTime;
import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.nio.graphml.GraphMLExporter;

import java.io.File;
import java.io.StringWriter;
import java.util.*;
import java.util.stream.Collectors;

public class GtfsTransit {

    static class TimeAggregator {
        List<Integer> times = new ArrayList<>();

        void add(int minutes) { times.add(minutes); }

        double avg() {
            return times.stream().mapToInt(Integer::intValue).average().orElse(0);
        }

        int min() { return times.stream().mapToInt(Integer::intValue).min().orElse(0); }
        int max() { return times.stream().mapToInt(Integer::intValue).max().orElse(0); }
        int count() { return times.size(); }
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: ./run.sh GtfsTransit <gtfs-file.zip> [output.graphml]");
            System.out.println("  Without output file: prints table to console");
            System.out.println("  With output file: saves GraphML for JGraphT analysis");
            return;
        }

        File gtfsFile = new File(args[0]);
        if (!gtfsFile.exists()) {
            System.out.println("Error: file not found: " + args[0]);
            return;
        }

        String outputFile = args.length > 1 ? args[1] : null;

        System.out.println("Loading GTFS feed...");
        GTFSFeed feed;
        try {
            feed = GTFSFeed.fromFile(args[0]);
        } catch (Exception e) {
            System.out.println("Error loading GTFS: " + e.getMessage());
            return;
        }

        // Find subway (1) and rail (2) routes
        Map<String, Route> transitRoutes = feed.routes.values().stream()
                .filter(r -> r.route_type == Route.SUBWAY || r.route_type == Route.RAIL)
                .collect(Collectors.toMap(r -> r.route_id, r -> r));

        if (transitRoutes.isEmpty()) {
            System.out.println("No subway or rail routes found.");
            feed.close();
            return;
        }

        System.out.println("Found " + transitRoutes.size() + " subway/rail route(s):\n");
        for (var entry : transitRoutes.entrySet()) {
            Route r = entry.getValue();
            System.out.printf("  %-4s  %s%n", entry.getKey(),
                    r.route_short_name != null ? r.route_short_name : "(no short name)");
        }
        System.out.println();

        // Aggregation: key = "routeId||fromStopId||toStopId"
        Map<String, TimeAggregator> agg = new LinkedHashMap<>();

        for (Trip trip : feed.trips.values()) {
            String routeId = trip.route_id;
            if (!transitRoutes.containsKey(routeId)) continue;

            Iterable<StopTime> stopTimesIterable = feed.getOrderedStopTimesForTrip(trip.trip_id);
            List<StopTime> stops = new ArrayList<>();
            for (StopTime st : stopTimesIterable) {
                stops.add(st);
            }

            if (stops.size() < 2) continue;

            for (int i = 0; i < stops.size() - 1; i++) {
                StopTime from = stops.get(i);
                StopTime to = stops.get(i + 1);
                int minutes = (to.arrival_time - from.departure_time) / 60;
                if (minutes < 0) continue;

                String key = routeId + "||" + from.stop_id + "||" + to.stop_id;
                agg.computeIfAbsent(key, k -> new TimeAggregator()).add(minutes);
            }
        }

        // Build stop name lookup (only for transit routes)
        Set<String> transitStopIds = new HashSet<>();
        Map<String, String> stopNames = new HashMap<>();
        for (Trip trip : feed.trips.values()) {
            if (!transitRoutes.containsKey(trip.route_id)) continue;
            Iterable<StopTime> stopTimesIterable = feed.getOrderedStopTimesForTrip(trip.trip_id);
            for (StopTime st : stopTimesIterable) {
                transitStopIds.add(st.stop_id);
            }
        }
        for (String stopId : transitStopIds) {
            Stop stop = feed.stops.get(stopId);
            if (stop != null) {
                stopNames.put(stopId, stop.stop_name);
            }
        }

        // Console output (table)
        System.out.println("=== Station-to-Station Travel Times ===\n");
        System.out.println("(avg min | range | count = number of trips this pair appears on)\n");

        List<Map.Entry<String, TimeAggregator>> sorted = agg.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.comparingDouble(TimeAggregator::avg)))
                .toList();

        System.out.printf("  %-8s %-25s -> %-25s %s%n", "Route", "From", "To", "Avg | Range | Count");
        System.out.println("  " + "-".repeat(110));

        for (var entry : sorted) {
            String[] parts = entry.getKey().split("\\|\\|", 3);
            String routeId = parts[0];
            String fromId = parts[1];
            String toId = parts[2];
            String routeName = transitRoutes.getOrDefault(routeId, new Route()).route_short_name;
            String fromName = stopNames.get(fromId);
            String toName = stopNames.get(toId);
            if ((fromName == null || fromName.isEmpty())) {
                fromName = fromId.substring(fromId.lastIndexOf(':') + 1);
            }
            if ((toName == null || toName.isEmpty())) {
                toName = toId.substring(toId.lastIndexOf(':') + 1);
            }
            if (fromName == null || fromName.isEmpty()) fromName = fromId;
            if (toName == null || toName.isEmpty()) toName = toId;
            TimeAggregator ta = entry.getValue();
            System.out.printf("  %-8s %-25s -> %-25s %.1f | %d-%d | %d%n",
                    routeName, fromName, toName, ta.avg(), ta.min(), ta.max(), ta.count());
        }

        System.out.println("\nTotal unique pairs: " + agg.size());

        // GraphML export
        if (outputFile != null) {
            DefaultDirectedWeightedGraph<String, DefaultEdge> graph = new DefaultDirectedWeightedGraph<>(DefaultEdge.class);

            // Add only transit stops as vertices
            // Use stop_name as node id when available, fallback to stop_id
            for (String stopId : transitStopIds) {
                Stop stop = feed.stops.get(stopId);
                String nodeName = stop != null && stop.stop_name != null && !stop.stop_name.isEmpty()
                        ? stop.stop_name : stopId;
                if (!graph.containsVertex(nodeName)) {
                    graph.addVertex(nodeName);
                }
            }

            // Add edges with weights
            for (var entry : agg.entrySet()) {
                String[] parts = entry.getKey().split("\\|\\|", 3);
                String fromId = parts[1];
                String toId = parts[2];
                TimeAggregator ta = entry.getValue();

                Stop fromStop = feed.stops.get(fromId);
                Stop toStop = feed.stops.get(toId);
                String fromNode = fromStop != null && fromStop.stop_name != null && !fromStop.stop_name.isEmpty()
                        ? fromStop.stop_name : fromId;
                String toNode = toStop != null && toStop.stop_name != null && !toStop.stop_name.isEmpty()
                        ? toStop.stop_name : toId;

                DefaultEdge edge = graph.addEdge(fromNode, toNode);
                if (edge != null) {
                    graph.setEdgeWeight(edge, ta.avg());
                }
            }

            // Export as GraphML, using stop names as node ids
            GraphMLExporter<String, DefaultEdge> exporter = new GraphMLExporter<>(
                vertex -> vertex);
            exporter.setExportEdgeWeights(true);
            StringWriter writer = new StringWriter();
            exporter.exportGraph(graph, writer);

            File outFile = new File(outputFile);
            try {
                java.nio.file.Files.writeString(outFile.toPath(), writer.toString());
            } catch (Exception e) {
                System.out.println("Error writing GraphML: " + e.getMessage());
                feed.close();
                return;
            }
            System.out.println("\nGraphML saved to: " + outFile.getAbsolutePath());
            System.out.println("Vertices: " + graph.vertexSet().size());
            System.out.println("Edges: " + graph.edgeSet().size());
        }

        feed.close();
    }
}
