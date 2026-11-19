// Basket maths. All basket amounts are integer cents.
const { query } = require('./02-db');
const { applyDiscount } = require('./05-discounts');

async function getBasketLines(basketId) {
  return query(
    'SELECT book_id, category, quantity, unit_price_cents FROM basket_lines WHERE basket_id = $1',
    [basketId],
  );
}

function subtotalCents(lines) {
  return lines.reduce((sum, line) => sum + line.quantity * line.unit_price_cents, 0);
}

async function computeTotal(basketId, discountCode) {
  const lines = await getBasketLines(basketId);
  const subtotal = subtotalCents(lines);
  const totalCents = discountCode ? applyDiscount(subtotal, discountCode) : subtotal;
  return { lines, subtotalCents: subtotal, totalCents };
}

module.exports = { getBasketLines, subtotalCents, computeTotal };
