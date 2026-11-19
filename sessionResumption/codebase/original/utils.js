// Small helpers.
function formatDate(value) {
  return new Date(value).toISOString().slice(0, 10);
}

function nights(stay) {
  return Math.round((stay.checkOut - stay.checkIn) / (24 * 60 * 60 * 1000));
}

module.exports = { formatDate, nights };
