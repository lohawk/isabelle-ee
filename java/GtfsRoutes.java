import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class GtfsRoutes {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java GtfsRoutes <gtfs-file.zip>");
            return;
        }

        String filePath = args[0];

        try {
            // GTFS is a ZIP file, so we open it with ZipFile
            ZipFile zipFile = new ZipFile(filePath);

            // Look for routes.txt in the ZIP
            ZipEntry entry = zipFile.getEntry("routes.txt");
            if (entry == null) {
                System.out.println("Error: routes.txt not found in the GTFS file.");
                return;
            }

            // Read the CSV file
            InputStream is = zipFile.getInputStream(entry);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));

            String line;
            boolean firstLine = true;
            List<String> headers = new ArrayList<>();

            while ((line = reader.readLine()) != null) {
                if (firstLine) {
                    // First line is the header row
                    headers = parseCsvLine(line);
                    firstLine = false;
                    System.out.println("Routes:");
                    System.out.println("------");
                    continue;
                }
                List<String> values = parseCsvLine(line);
                printRoute(headers, values);
            }

            reader.close();
            zipFile.close();

        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    // Simple CSV parser — handles most GTFS files
    // GTFS CSVs are comma-separated, with optional double-quoted fields
    static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString().trim());
        return fields;
    }

    // Print route info using common GTFS route columns
    static void printRoute(List<String> headers, List<String> values) {
        String routeId = getHeader(headers, values, "route_id");
        String routeShortName = getHeader(headers, values, "route_short_name");
        String routeLongName = getHeader(headers, values, "route_long_name");
        String routeType = getHeader(headers, values, "route_type");

        // route_type codes: 0=Tram, 1=Subway, 2=Rail, 3=Bus, 4=Ferry, 5=Cable car, 7=Monorail
        String type = getRouteTypeName(routeType);

        System.out.printf("  ID: %-8s  Name: %-12s  Long: %-30s  Type: %s%n",
                routeId, routeShortName, routeLongName, type);
    }

    static String getHeader(List<String> headers, List<String> values, String headerName) {
        int index = headers.indexOf(headerName);
        if (index >= 0 && index < values.size()) {
            return values.get(index);
        }
        return "(n/a)";
    }

    static String getRouteTypeName(String routeType) {
        if (routeType == null || routeType.equals("(n/a)")) return "(n/a)";
        return switch (routeType) {
            case "0" -> "Tram/Streetcar";
            case "1" -> "Subway";
            case "2" -> "Rail";
            case "3" -> "Bus";
            case "4" -> "Ferry";
            case "5" -> "Cable car";
            case "7" -> "Monorail";
            default -> "Unknown (" + routeType + ")";
        };
    }
}
