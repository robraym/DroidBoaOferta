package br.com.droidboaoferta;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PropertyPageParser {
    private static final Pattern NEXT_DATA = Pattern.compile(
            "(?is)<script[^>]*id=[\\\"']__NEXT_DATA__[\\\"'][^>]*>(.*?)</script>"
    );

    private PropertyPageParser() {
    }

    static PropertyPageResult parse(String html) {
        if (html == null || html.trim().isEmpty()) {
            return new PropertyPageResult("", new ArrayList<>());
        }
        Matcher matcher = NEXT_DATA.matcher(html);
        if (!matcher.find()) {
            return new PropertyPageResult("", new ArrayList<>());
        }
        try {
            JSONObject root = new JSONObject(matcher.group(1));
            PropertyPageResult loftResult = parseLoft(root, html);
            if (loftResult != null) {
                return loftResult;
            }
            JSONObject listings = findObjectContainingArray(root, "saleListings");
            String condominiumName = findString(root, "nameFormatted");
            List<PropertyPageListing> parsed = new ArrayList<>();
            Set<String> seenIds = new HashSet<>();
            if (listings == null) {
                return new PropertyPageResult(condominiumName, parsed);
            }
            JSONArray saleListings = listings.optJSONArray("saleListings");
            if (saleListings == null) {
                return new PropertyPageResult(condominiumName, parsed);
            }
            for (int index = 0; index < saleListings.length(); index++) {
                JSONObject wrapper = saleListings.optJSONObject(index);
                if (wrapper == null) {
                    continue;
                }
                JSONObject source = wrapper.optJSONObject("_source");
                if (source == null) {
                    source = wrapper;
                }
                String id = wrapper.optString("_id", source.optString("id", ""));
                double area = source.optDouble("area", Double.NaN);
                double salePrice = source.optDouble("salePrice", Double.NaN);
                if (id.isEmpty() || !(area > 0d) || !(salePrice > 0d)
                        || !seenIds.add(id)) {
                    continue;
                }
                parsed.add(new PropertyPageListing(
                        id,
                        area,
                        salePrice,
                        source.optString("shortSaleDescription", ""),
                        PropertyPageClient.buildListingUrl(
                                id,
                                isQuintoAndarClassified(wrapper, source)
                        ),
                        containsTag(source.optJSONArray("listingTags"), "NEW_AD")
                ));
            }
            return new PropertyPageResult(condominiumName, parsed);
        } catch (Exception ignored) {
            return new PropertyPageResult("", new ArrayList<>());
        }
    }

    static PropertyPageResult parseLoftApiResponse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new PropertyPageResult("", new ArrayList<>());
        }
        try {
            JSONObject condominium = new JSONObject(json);
            PropertyPageResult result = parseLoftCondominium(condominium, "");
            return result == null
                    ? new PropertyPageResult("", new ArrayList<>())
                    : result;
        } catch (Exception ignored) {
            return new PropertyPageResult("", new ArrayList<>());
        }
    }

    private static boolean isQuintoAndarClassified(JSONObject wrapper, JSONObject source) {
        return "CLASSIFIED".equals(wrapper.optString("origin", ""))
                || containsTag(source.optJSONArray("listingTags"), "CLASSIFIED");
    }

    private static PropertyPageResult parseLoft(JSONObject root, String html) {
        JSONObject condominium = findLoftCondominium(root);
        if (condominium == null) {
            return null;
        }
        return parseLoftCondominium(condominium, html);
    }

    private static PropertyPageResult parseLoftCondominium(JSONObject condominium, String html) {
        JSONArray listings = condominium.optJSONArray("listings");
        if (listings == null) {
            return null;
        }
        String condominiumName = condominium.optString("name", "");
        List<PropertyPageListing> parsed = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (int index = 0; index < listings.length(); index++) {
            JSONObject listing = listings.optJSONObject(index);
            if (listing == null || !"FOR_SALE".equals(listing.optString("status", ""))
                    || !seenIds.add(listing.optString("id", ""))) {
                continue;
            }
            String id = listing.optString("id", "");
            double area = listing.optDouble("area", Double.NaN);
            double price = listing.optDouble("price", Double.NaN);
            if (id.isEmpty() || !(area > 0d) || !(price > 0d)) {
                continue;
            }
            parsed.add(new PropertyPageListing(
                    id,
                    area,
                    price,
                    buildLoftDescription(listing),
                    findLoftListingUrl(html, id),
                    false
            ));
        }
        return new PropertyPageResult(condominiumName, parsed);
    }

    private static JSONObject findLoftCondominium(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (object.optJSONArray("listings") != null
                    && !object.optString("shortId", "").isEmpty()
                    && !object.optString("name", "").isEmpty()) {
                return object;
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                JSONObject found = findLoftCondominium(object.opt(keys.next()));
                if (found != null) {
                    return found;
                }
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) {
                JSONObject found = findLoftCondominium(array.opt(index));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String buildLoftDescription(JSONObject listing) {
        String type = listing.optString(
                "propertyType",
                listing.optString("type", "Apartamento")
        );
        if ("studio".equalsIgnoreCase(type)) {
            type = "Studio";
        } else if ("rooftop".equalsIgnoreCase(type)) {
            type = "Cobertura";
        } else {
            type = "Apartamento";
        }
        int bedrooms = listing.optInt("bedrooms", 0);
        return bedrooms > 0 ? type + " com " + bedrooms + " quartos" : type;
    }

    private static String findLoftListingUrl(String html, String id) {
        if (html == null || id == null || id.trim().isEmpty()) {
            return "";
        }
        Pattern pattern = Pattern.compile(
                "https://loft\\.com\\.br/imovel/[^\\\"#]*?/" + Pattern.quote(id.trim())
        );
        Matcher matcher = pattern.matcher(html);
        return matcher.find() ? matcher.group() : PropertyPageClient.buildLoftListingUrl(id);
    }

    static PropertyListingMetadata parseListingMetadata(String html) {
        if (html == null || html.trim().isEmpty()) {
            return PropertyListingMetadata.empty();
        }
        Matcher matcher = NEXT_DATA.matcher(html);
        if (!matcher.find()) {
            return PropertyListingMetadata.empty();
        }
        try {
            JSONObject root = new JSONObject(matcher.group(1));
            return new PropertyListingMetadata(
                    parseDate(findString(root, "firstPublicationDate")),
                    parseDate(findString(root, "lastPublicationDate")),
                    findPositiveDouble(root, "salePrice")
            );
        } catch (Exception ignored) {
            return PropertyListingMetadata.empty();
        }
    }

    private static boolean containsTag(JSONArray tags, String expected) {
        if (tags == null) {
            return false;
        }
        for (int index = 0; index < tags.length(); index++) {
            if (expected.equals(tags.optString(index))) {
                return true;
            }
        }
        return false;
    }

    private static long parseDate(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0L;
        }
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
                "yyyy-MM-dd'T'HH:mm:ss.SSSX"
        };
        for (String pattern : patterns) {
            try {
                return new java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                        .parse(value).getTime();
            } catch (Exception ignored) {
            }
        }
        return 0L;
    }

    private static JSONObject findObjectContainingArray(Object value, String key) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (object.optJSONArray(key) != null) {
                return object;
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                JSONObject found = findObjectContainingArray(object.opt(keys.next()), key);
                if (found != null) {
                    return found;
                }
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) {
                JSONObject found = findObjectContainingArray(array.opt(index), key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String findString(Object value, String key) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String direct = object.optString(key, "");
            if (!direct.trim().isEmpty()) {
                return direct;
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String found = findString(object.opt(keys.next()), key);
                if (!found.isEmpty()) {
                    return found;
                }
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) {
                String found = findString(array.opt(index), key);
                if (!found.isEmpty()) {
                    return found;
                }
            }
        }
        return "";
    }

    private static double findPositiveDouble(Object value, String key) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            double direct = object.optDouble(key, Double.NaN);
            if (direct > 0d) {
                return direct;
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                double found = findPositiveDouble(object.opt(keys.next()), key);
                if (found > 0d) {
                    return found;
                }
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) {
                double found = findPositiveDouble(array.opt(index), key);
                if (found > 0d) {
                    return found;
                }
            }
        }
        return Double.NaN;
    }
}
