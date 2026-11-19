// Card payments. The gateway expects integer cents.
const gateway = require('./paymentGateway');

async function charge(order, cardToken) {
  const result = await gateway.charge({
    amount: order.totalCents,
    currency: 'GBP',
    source: cardToken,
  });
  if (result.status !== 'succeeded') {
    const error = new Error('Payment failed: ' + result.failureCode);
    error.status = 402;
    throw error;
  }
  return result.id;
}

async function refund(paymentId, amountCents) {
  return gateway.refund({ payment: paymentId, amount: amountCents });
}

module.exports = { charge, refund };
