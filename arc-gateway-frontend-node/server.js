const path = require("path");
const express = require("express");

const app = express();
const port = Number(process.env.PORT || 3000);

app.disable("x-powered-by");
app.use(express.json({ limit: "1mb" }));

app.get("/healthz", (_req, res) => {
  res.status(200).json({ status: "ok", service: "arc-gateway-frontend-node" });
});

app.use(express.static(path.join(__dirname, "public"), {
  etag: true,
  maxAge: "1h",
  index: "index.html"
}));

app.get("*", (_req, res) => {
  res.sendFile(path.join(__dirname, "public", "index.html"));
});

app.listen(port, () => {
  console.log(`frontend-node listening on :${port}`);
});
