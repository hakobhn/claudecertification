// VAT. Rates are stored as whole percentages (20 means 20%).
const VAT_RATES = { books: 0, ebooks: 20, gifts: 20 };
const DEFAULT_RATE = 20;

function vatCents(lines) {
  return lines.reduce((sum, line) => {
    const rate = VAT_RATES[line.category] ?? DEFAULT_RATE;
    return sum + Math.round(line.quantity * line.unit_price_cents * rate);
  }, 0);
}

function priceWithVatCents(lines) {
  const net = lines.reduce((sum, line) => sum + line.quantity * line.unit_price_cents, 0);
  return net + vatCents(lines);
}

module.exports = { VAT_RATES, vatCents, priceWithVatCents };
