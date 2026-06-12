// Auto error reporting for viewmaxxing. The app POSTs playback/resolution
// failures here so dead embed mirrors + scraper drift surface without the
// user screenshotting the in-app debug log. Events go to KV (30-day TTL).
// A token-gated GET lists recent events for triage.
export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "POST") {
      let body;
      try {
        body = await request.json();
      } catch {
        return json({ error: "bad json" }, 400);
      }
      const ts = Date.now();
      // Descending-encoded timestamp so KV list() returns newest first.
      const key = `${(1e15 - ts).toString().padStart(16, "0")}-${Math.random().toString(36).slice(2, 8)}`;
      await env.TELEMETRY.put(key, JSON.stringify({ ts, ...body }), {
        expirationTtl: 60 * 60 * 24 * 30,
      });
      return new Response(null, { status: 204, headers: cors() });
    }

    if (request.method === "GET") {
      // Triage endpoint: /report?token=...&limit=50
      if (url.searchParams.get("token") !== env.REPORT_TOKEN) {
        return json({ error: "unauthorized" }, 401);
      }
      const limit = Math.min(parseInt(url.searchParams.get("limit") || "50", 10), 200);
      const list = await env.TELEMETRY.list({ limit });
      const events = [];
      for (const k of list.keys) {
        const v = await env.TELEMETRY.get(k.name);
        if (v) events.push(JSON.parse(v));
      }
      return json({ count: events.length, events });
    }

    return new Response("ok", { headers: cors() });
  },
};

function cors() {
  return { "Access-Control-Allow-Origin": "*" };
}
function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "Content-Type": "application/json", ...cors() },
  });
}
