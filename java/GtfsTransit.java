import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.Route;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.gtfs.model.Trip;
import com.conveyal.gtfs.model.StopTime;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

public class GtfsTransit {

    // Aggregation: key = "fromStopId:toStopId", value = list of travel times in minutes
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
            System.out.println("Usage: ./run.sh GtfsTransit <gtfs-file.zip>");
            return;
        }

        File gtfsFile = new File(args[0]);
        if (!gtfsFile.exists()) {
            System.out.println("Error: file not found: " + args[0]);
            return;
        }

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

        // Aggregate station-to-station travel times across all trips
        Map<String, TimeAggregator> agg = new LinkedHashMap<>();

        for (Trip trip : feed.trips.values()) {
            if (!transitRoutes.containsKey(trip.route_id)) continue;

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

                String key = from.stop_id + ":" + to.stop_id;
                agg.computeIfAbsent(key, k -> new TimeAggregator()).add(minutes);
            }
        }

        // Build stop name lookup
        Map<String, String> stopNames = new HashMap<>();
        for (var entry : feed.stops.entrySet()) {
            Stop stop = entry.getValue();
            stopNames.put(entry.getKey(), stop.stop_name);
        }

        // Output all unique station-to-station pairs sorted by average time
        System.out.println("=== Station-to-Station Travel Times ===\n");
        System.out.println("(avg min | range | count = number of trips this pair appears on)\n");

        // Sort pairs by average time
        List<Map.Entry<String, TimeAggregator>> sorted = agg.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.comparingDouble(TimeAggregator::avg)))
                .toList();

        System.out.printf("  %-25s -> %-25s %s%n", "From", "To", "Avg | Range | Count");
        System.out.println("  " + "-".repeat(85));

        for (var entry : sorted) {
            String[] parts = entry.getKey().split(":");
            String fromName = stopNames.getOrDefault(parts[0], parts[0]);
            String toName = stopNames.getOrDefault(parts[1], parts[1]);
            TimeAggregator ta = entry.getValue();
            System.out.printf("  %-25s -> %-25s %.1f | %d-%d | %d%n",
                    fromName, toName, ta.avg(), ta.min(), ta.max(), ta.count());
        }

        System.out.println("\nTotal unique pairs: " + agg.size());

        feed.close();
    }
}
