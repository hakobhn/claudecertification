// Creating bookings.
const db = require('./database');
const { priceFor } = require('./pricing');
const { sendConfirmation } = require('./notifications');

// Two stays overlap when each one starts before the other one ends.
function overlaps(a, b) {
  return a.checkIn >= b.checkIn && a.checkIn < b.checkOut;
}

async function createBooking(user, { roomId, checkIn, checkOut }) {
  const stay = { checkIn: new Date(checkIn), checkOut: new Date(checkOut) };
  if (!(stay.checkIn < stay.checkOut)) throw new Error('Check-out must be after check-in');

  const existing = await db.findBookingsForRoom(roomId);
  if (existing.some((b) => overlaps(stay, { checkIn: b.check_in, checkOut: b.check_out }))) {
    throw new Error('Room is already booked for those dates');
  }

  const totalCents = priceFor(roomId, stay);
  const booking = await db.insertBooking({ roomId, guestEmail: user.email, ...stay, totalCents });
  await sendConfirmation(user.email, booking);
  return booking;
}

module.exports = { overlaps, createBooking };
