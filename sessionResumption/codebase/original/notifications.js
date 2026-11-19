// Booking e-mails.
const mailer = require('./mailer');
const { formatDate } = require('./utils');
const { HOTEL_NAME } = require('./config');

async function sendConfirmation(email, booking) {
  await mailer.send({
    to: email,
    subject: `${HOTEL_NAME}: booking ${booking.id} confirmed`,
    text: `Room ${booking.room_id}, ${formatDate(booking.check_in)} to ${formatDate(booking.check_out)}.`,
  });
}

module.exports = { sendConfirmation };
