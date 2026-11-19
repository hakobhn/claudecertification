// Plain-text receipts.
const { calculateTotal } = require('./04-basket');
const { shippingCents } = require('./08-shipping');
const { vatCents } = require('./13-tax');

function formatPounds(cents) {
  return '£' + (cents / 100).toFixed(2);
}

async function buildReceipt(order) {
  const { lines, totalCents } = await calculateTotal(order.basketId, order.discountCode);
  return [
    'Subtotal: ' + formatPounds(totalCents),
    'Shipping: ' + formatPounds(shippingCents(order)),
    'VAT: ' + formatPounds(vatCents(lines)),
  ].join('\n');
}

module.exports = { buildReceipt };
