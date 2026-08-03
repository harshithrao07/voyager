import http from "node:http";
import fs from "node:fs";
import path from "node:path";

const port = Number(process.env.PORT || 8080);
const dataDir = process.env.DATA_DIR || "/data";
const ledgerPath = path.join(dataDir, "invocations.jsonl");
fs.mkdirSync(dataDir, { recursive: true });

let records = [];
if (fs.existsSync(ledgerPath)) {
  records = fs.readFileSync(ledgerPath, "utf8")
    .split(/\r?\n/)
    .filter(Boolean)
    .map((line) => JSON.parse(line));
}

function send(response, status, body) {
  const json = JSON.stringify(body);
  response.writeHead(status, {
    "content-type": "application/json",
    "content-length": Buffer.byteLength(json),
  });
  response.end(json);
}

function readBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    request.on("data", (chunk) => {
      size += chunk.length;
      if (size > 1024 * 1024) {
        reject(new Error("request body exceeds 1 MiB"));
        request.destroy();
        return;
      }
      chunks.push(chunk);
    });
    request.on("end", () => {
      const raw = Buffer.concat(chunks).toString("utf8");
      if (!raw) {
        resolve({});
        return;
      }
      try {
        resolve(JSON.parse(raw));
      } catch (error) {
        reject(error);
      }
    });
    request.on("error", reject);
  });
}

const server = http.createServer(async (request, response) => {
  const url = new URL(request.url, `http://${request.headers.host}`);

  if (request.method === "GET" && url.pathname === "/health") {
    send(response, 200, { status: "UP", records: records.length });
    return;
  }

  if (request.method === "POST" && url.pathname === "/reset") {
    records = [];
    fs.writeFileSync(ledgerPath, "");
    send(response, 200, { reset: true });
    return;
  }

  if (request.method === "GET" && url.pathname === "/invocations") {
    const runId = url.searchParams.get("runId");
    const selected = runId
      ? records.filter((record) => record.runId === runId)
      : records;
    send(response, 200, selected);
    return;
  }

  if (request.method === "POST" && url.pathname === "/invocations") {
    try {
      const body = await readBody(request);
      const record = {
        sequence: records.length + 1,
        receivedAt: new Date().toISOString(),
        runId: body.runId ?? null,
        workload: body.workload ?? null,
        operationId: body.operationId ?? null,
        workflowExecutionId:
          request.headers["x-voyager-workflow-execution-id"] ??
          body.workflowExecutionId ?? null,
        stateExecutionAttemptId:
          request.headers["x-voyager-state-execution-attempt-id"] ?? null,
        stateName:
          request.headers["x-voyager-state-name"] ?? body.stateName ?? null,
        body,
      };
      records.push(record);
      fs.appendFileSync(ledgerPath, `${JSON.stringify(record)}\n`);
      send(response, 200, {
        recorded: true,
        sequence: record.sequence,
        stateExecutionAttemptId: record.stateExecutionAttemptId,
      });
    } catch (error) {
      send(response, 400, { error: error.message });
    }
    return;
  }

  send(response, 404, { error: "not found" });
});

server.listen(port, "0.0.0.0", () => {
  process.stdout.write(`crash-recovery counter listening on ${port}\n`);
});
