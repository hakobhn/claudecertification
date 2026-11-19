// Deposits for bookings. The gateway expects integer cents.
const gateway = require('./paymentGateway');
const { DEPOSIT_PERCENT } = require('./config');

function depositCents(booking) {
  return Math.round((booking.total_cents * DEPOSIT_PERCENT) / 100);
}

async function takeDeposit(booking, cardToken) {
  const result = await gateway.charge({ amount: depositCents(booking), currency: 'EUR', source: cardToken });
  if (result.status !== 'succeeded') throw new Error(`Deposit failed: ${result.failureCode}`);
  return result.id;
}

module.exports = { depositCents, takeDeposit };
