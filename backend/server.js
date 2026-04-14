const express = require("express");
const app = express();

app.get("/", (req, res) => {
  res.send("Backend chal raha hai 🚀");
});

app.get("/api", (req, res) => {
  res.json({ message: "Hello from backend 🔥" });
});

app.listen(3000);
