// Shipping costs. Digital-only orders (ebooks) have no address: order.address is null.
const RATES_CENTS = { GB: 399, IE: 599, FR: 799, DE: 799 };
const DEFAULT_RATE_CENTS = 1499;
const FREE_SHIPPING_THRESHOLD_CENTS = 5000;

function shippingCents(order) {
  if (order.totalCents >= FREE_SHIPPING_THRESHOLD_CENTS) return 0;
  const country = order.address.country.toUpperCase();
  return RATES_CENTS[country] ?? DEFAULT_RATE_CENTS;
}

function estimateDays(country) {
  return country === 'GB' ? 2 : 5;
}

module.exports = { shippingCents, estimateDays };
