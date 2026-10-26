#!/usr/bin/env bash
# Checks that each art API the app calls still answers with a 2xx status and
# the JSON keys the app parses. Runs every check, then exits 1 if any failed.
# Needs curl and jq (both preinstalled on ubuntu-latest runners).
set -uo pipefail

command -v curl >/dev/null || { echo "curl is required" >&2; exit 2; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }

# Same identification the app sends; museums and Wikimedia ask for one.
UA="fArtSaver-source-check/1.0 (https://github.com/phrag/ArtDream; Android TV screensaver)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
failures=0

# check SLUG LABEL URL JQ_FILTER [EXTRA_HEADER]
# Saves the body to $TMP/SLUG.json. Passes when the status is 2xx and the filter
# (jq -e) is truthy.
check() {
    local slug="$1" label="$2" url="$3" filter="$4" extra="${5:-}"
    local body="$TMP/$slug.json" code
    local -a hdr=()
    [[ -n "$extra" ]] && hdr=(-H "$extra")
    # Museum CDNs sometimes block one request and accept the next, so a
    # non-2xx gets up to two more tries before it counts as a failure.
    for attempt in 1 2 3; do
        code=$(curl -sS -A "$UA" "${hdr[@]}" --max-time 30 \
            -o "$body" -w '%{http_code}' "$url") || code="000"
        [[ "$code" =~ ^2[0-9][0-9]$ ]] && break
        (( attempt < 3 )) && sleep 15
    done
    if [[ ! "$code" =~ ^2[0-9][0-9]$ ]]; then
        echo "FAIL $label: HTTP $code" >&2
        echo "     $url" >&2
        failures=$((failures + 1))
        return 1
    fi
    if ! jq -e "$filter" "$body" >/dev/null 2>&1; then
        echo "FAIL $label: JSON is missing an expected key ($filter)" >&2
        echo "     $url" >&2
        failures=$((failures + 1))
        return 1
    fi
    echo "ok   $label"
}

# --- Cleveland Museum of Art (ClevelandSource.kt) ---
check cma "Cleveland Museum of Art" \
    "https://openaccess-api.clevelandart.org/api/artworks/?cc0=1&has_image=1&type=Painting&limit=1" \
    '(.info.total | type == "number") and (.data | type == "array")'

# --- The Met (MetSource.kt): search, then one object, as the app does ---
met_base="https://collectionapi.metmuseum.org/public/collection"
met_search="$met_base/v1.1/search?departmentId=11&hasImages=true&isPublicDomain=true&q=painting&limit=1&offset=0"
if check metsearch "The Met search" "$met_search" \
    '(.total | type == "number") and has("objectIDs")'; then
    met_id=$(jq -r '.objectIDs[0] // empty' "$TMP/metsearch.json")
    if [[ -n "$met_id" ]]; then
        check metobject "The Met object" "$met_base/v1/objects/$met_id" \
            'has("objectID") and has("primaryImage") and has("isPublicDomain")'
    else
        echo "FAIL The Met object: search returned no objectIDs" >&2
        failures=$((failures + 1))
    fi
else
    echo "FAIL The Met object: skipped, search failed" >&2
    failures=$((failures + 1))
fi

# --- Art Institute of Chicago (ArticSource.kt) ---
artic_params='{"query":{"bool":{"must":[{"term":{"is_public_domain":true}},{"match":{"artwork_type_title":"Painting"}}]}},"fields":["id","title","artist_title","date_display","image_id","thumbnail"],"limit":1,"page":1}'
artic_url="https://api.artic.edu/api/v1/artworks/search?params=$(jq -rn --arg p "$artic_params" '$p | @uri')"
# AIC reads its own AIC-User-Agent header and rejects requests without it.
check artic "Art Institute of Chicago" "$artic_url" \
    '(.data | type == "array") and (.data[0] | has("id") and has("image_id") and has("thumbnail"))' \
    "AIC-User-Agent: $UA"

# --- NASA Image Library (NasaSource.kt) ---
check nasa "NASA Image Library" \
    "https://images-api.nasa.gov/search?media_type=image&q=nebula&page=1&page_size=1" \
    '(.collection.metadata.total_hits | type == "number") and (.collection.items | type == "array")'

# --- Wikimedia Commons featured pictures (WikimediaSource.kt) ---
wm_query() {
    echo "https://commons.wikimedia.org/w/api.php?action=query&format=json&formatversion=2&generator=categorymembers&gcmtitle=Category%3A$1&gcmtype=file&gcmsort=sortkey&gcmlimit=5&prop=imageinfo&iiprop=url%7Csize%7Cmime%7Cextmetadata&iiurlwidth=3000&iiextmetadatafilter=LicenseShortName%7CArtist%7CObjectName%7CDateTimeOriginal"
}
for cat in Featured_pictures_of_landscapes Featured_pictures_of_astronomy Featured_pictures_of_cityscapes; do
    check "wm_$cat" "Wikimedia Commons ($cat)" "$(wm_query "$cat")" \
        '(.query.pages | type == "array") and (.query.pages | length > 0) and (.query.pages[0] | has("pageid") and has("title"))'
done

echo
if (( failures > 0 )); then
    echo "$failures check(s) failed" >&2
    exit 1
fi
echo "All art sources answered as expected."
