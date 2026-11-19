// Invoices. Guest checkouts have no customer record: orders.customer_id is NULL.
const { queryOne } = require('./02-db');
const { requireUser } = require('./01-auth');
const { sendInvoiceEmail } = require('./mailer');

async function loadOrderWithCustomer(orderId) {
  const order = await queryOne('SELECT * FROM orders WHERE id = $1', [orderId]);
  if (!order) return null;
  order.customer = order.customer_id
    ? await queryOne('SELECT name, email FROM customers WHERE id = $1', [order.customer_id])
    : null;
  return order;
}

// POST /orders/:id/invoice
async function emailInvoice(req, res) {
  const userId = requireUser(req);
  const order = await loadOrderWithCustomer(req.params.id);
  if (!order || order.user_id !== userId) return res.status(404).json({ error: 'Order not found' });
  const to = order.customer.email;
  await sendInvoiceEmail(to, order);
  res.status(202).end();
}

module.exports = { loadOrderWithCustomer, emailInvoice };
