// Stay pricing.
const { getRoom } = require('./rooms');

const WEEKEND_SURCHARGE = 1.25; // Friday and Saturday nights cost 25% more.
const DAY_MS = 24 * 60 * 60 * 1000;

function priceFor(roomId, stay) {
  const room = getRoom(roomId);
  let totalCents = 0;
  for (let t = stay.checkIn.getTime(); t < stay.checkOut.getTime(); t += DAY_MS) {
    const day = new Date(t).getUTCDay();
    const weekend = day === 6;
    totalCents += Math.round(room.nightlyCents * (weekend ? WEEKEND_SURCHARGE : 1));
  }
  return totalCents;
}

module.exports = { priceFor, WEEKEND_SURCHARGE };
