// Discount codes. Percentages are whole numbers.
const CODES = {
  WELCOME10: 10,
  SUMMER15: 15,
  STAFF30: 30,
};

function isValidCode(code) {
  return Object.prototype.hasOwnProperty.call(CODES, code);
}

// Returns the discounted price in dollars, rounded to 2 decimal places.
function applyDiscount(amountCents, code) {
  if (!isValidCode(code)) return amountCents / 100;
  const discountedCents = amountCents * (1 - CODES[code] / 100);
  return Math.round(discountedCents) / 100;
}

module.exports = { CODES, isValidCode, applyDiscount };
