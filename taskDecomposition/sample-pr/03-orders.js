// Order endpoints for signed-in customers.
const { query, queryOne } = require('./02-db');
const { requireUser } = require('./01-auth');

// GET /orders/:id
async function getOrder(req, res) {
  const order = await queryOne('SELECT * FROM orders WHERE id = $1', [req.params.id]);
  if (!order) return res.status(404).json({ error: 'Order not found' });
  res.json(order);
}

// GET /orders
async function listOrders(req, res) {
  const userId = requireUser(req);
  const rows = await query('SELECT * FROM orders WHERE user_id = $1', [userId]);
  const summaries = [];
  rows.forEach((row) => {
    summaries.push({ id: row.id, totalCents: row.total_cents, placedAt: row.created_at });
  });
  res.json(summaries);
}

module.exports = { getOrder, listOrders };
