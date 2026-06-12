// Thin TMDb proxy for the viewmaxxing "Must Watch" row.
//
// Holds the TMDb API key as a Worker secret (env.TMDB_API_KEY) so it never
// ships inside the sideloaded APK. Forwards GET /3/* to api.themoviedb.org
// with the key appended, and caches responses for 10 minutes so the trending
// row is cheap. Anything that isn't a GET /3/* TMDb path is rejected, so it
// can't be used as an open proxy.
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method !== "GET" || !url.pathname.startsWith("/3/")) {
      return new Response("not found", { status: 404 });
    }
    const upstream = new URL("https://api.themoviedb.org" + url.pathname);
    for (const [k, v] of url.searchParams) {
      if (k.toLowerCase() !== "api_key") upstream.searchParams.set(k, v);
    }
    upstream.searchParams.set("api_key", env.TMDB_API_KEY);

    const resp = await fetch(upstream.toString(), {
      headers: { Accept: "application/json" },
      cf: { cacheTtl: 600, cacheEverything: true },
    });
    return new Response(resp.body, {
      status: resp.status,
      headers: {
        "Content-Type": "application/json",
        "Cache-Control": "public, max-age=600",
        "Access-Control-Allow-Origin": "*",
      },
    });
  },
};
