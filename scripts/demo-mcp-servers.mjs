import http from 'node:http';

const port = Number.parseInt(globalThis.process?.env?.DEMO_MCP_PORT ?? '48765', 10);

const catalogs = {
  '/crm': [
    {
      name: 'get_customer',
      description: 'Load a customer profile by customerId before preparing an order notification.',
      inputSchema: {
        type: 'object',
        properties: { customerId: { type: 'string' } },
        required: ['customerId'],
        additionalProperties: false,
      },
    },
    {
      name: 'search_customers',
      description: 'Search customer profiles by email address.',
      inputSchema: {
        type: 'object',
        properties: { email: { type: 'string' } },
        required: ['email'],
        additionalProperties: false,
      },
    },
  ],
  '/fulfillment': [
    {
      name: 'reserve_inventory',
      description: 'Reserve inventory for every line item in an order before shipment creation.',
      inputSchema: {
        type: 'object',
        properties: {
          orderId: { type: 'string' },
          items: { type: 'array' },
        },
        required: ['orderId', 'items'],
        additionalProperties: false,
      },
    },
    {
      name: 'create_shipment',
      description: 'Create a shipment for a reserved order and return its shipmentId and trackingNumber.',
      inputSchema: {
        type: 'object',
        properties: {
          orderId: { type: 'string' },
          reservationId: { type: 'string' },
          address: { type: 'object' },
        },
        required: ['orderId', 'reservationId', 'address'],
        additionalProperties: false,
      },
    },
  ],
};

function result(id, value) {
  return { jsonrpc: '2.0', id, result: value };
}

function error(id, code, message) {
  return { jsonrpc: '2.0', id, error: { code, message } };
}

function toolResult(pathname, name, args) {
  if (pathname === '/crm' && name === 'get_customer') {
    return { customerId: args.customerId, name: 'Demo Customer', email: 'customer@example.test' };
  }
  if (pathname === '/crm' && name === 'search_customers') {
    return { customers: [{ customerId: 'cust-demo-1', email: args.email }] };
  }
  if (pathname === '/fulfillment' && name === 'reserve_inventory') {
    return { orderId: args.orderId, reservationId: `res-${args.orderId}`, reserved: true };
  }
  if (pathname === '/fulfillment' && name === 'create_shipment') {
    return {
      orderId: args.orderId,
      shipmentId: `ship-${args.orderId}`,
      trackingNumber: 'DEMO-TRACK-001',
    };
  }
  return null;
}

function handleMessage(pathname, message) {
  if (message.id === undefined || message.id === null) {
    return null;
  }
  if (message.method === 'initialize') {
    return result(message.id, {
      protocolVersion: message.params?.protocolVersion ?? '2025-11-25',
      capabilities: { tools: { listChanged: false } },
      serverInfo: { name: `voyager-demo-${pathname.slice(1)}`, version: '1.0.0' },
    });
  }
  if (message.method === 'tools/list') {
    return result(message.id, { tools: catalogs[pathname] });
  }
  if (message.method === 'tools/call') {
    const value = toolResult(pathname, message.params?.name, message.params?.arguments ?? {});
    if (value === null) {
      return error(message.id, -32602, `Unknown tool: ${message.params?.name}`);
    }
    return result(message.id, {
      content: [{ type: 'text', text: JSON.stringify(value) }],
      structuredContent: value,
      isError: false,
    });
  }
  return error(message.id, -32601, `Unsupported method: ${message.method}`);
}

const server = http.createServer((request, response) => {
  const pathname = new URL(request.url ?? '/', 'http://localhost').pathname;
  if (request.method === 'GET' && pathname === '/health') {
    response.writeHead(200, { 'content-type': 'application/json' });
    response.end(JSON.stringify({ ok: true, endpoints: Object.keys(catalogs) }));
    return;
  }
  if (request.method === 'DELETE' && catalogs[pathname]) {
    response.writeHead(202);
    response.end();
    return;
  }
  if (request.method !== 'POST' || !catalogs[pathname]) {
    response.writeHead(request.method === 'GET' ? 405 : 404);
    response.end();
    return;
  }

  let body = '';
  request.setEncoding('utf8');
  request.on('data', (chunk) => { body += chunk; });
  request.on('end', () => {
    try {
      const responseMessage = handleMessage(pathname, JSON.parse(body));
      if (responseMessage === null) {
        response.writeHead(202);
        response.end();
        return;
      }
      response.writeHead(200, { 'content-type': 'application/json' });
      response.end(JSON.stringify(responseMessage));
    } catch {
      response.writeHead(400, { 'content-type': 'application/json' });
      response.end(JSON.stringify(error(null, -32700, 'Parse error')));
    }
  });
});

server.listen(port, '0.0.0.0', () => {
  console.log(`Voyager demo MCP servers listening on http://0.0.0.0:${port}`);
});

if (typeof globalThis.process?.on === 'function') {
  for (const signal of ['SIGINT', 'SIGTERM']) {
    globalThis.process.on(signal, () => server.close(() => globalThis.process.exit(0)));
  }
}

export { server };
