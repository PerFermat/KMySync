package de.spahr.ausgaben.wear;

/**
 * Entfernung zwischen zwei Punkten auf der Erde (Haversine).
 *
 * <p>Bewusst eine kleine Kopie aus {@code de.spahr.ausgaben.location.Geo} des Handy-Moduls: Uhr und
 * Handy teilen keinen Code, und für eine einzige Formel lohnt kein gemeinsames Modul. Wird die Formel
 * dort je angefaßt, muß sie hier mitgehen – daran hängt aber nur die Reihenfolge einer kurzen Liste,
 * nicht die Buchung selbst.</p>
 */
final class WearGeo {

    private WearGeo() {
    }

    static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
