// Database access for the room booking service.
const { Pool } = require('pg');

const pool = new Pool({ connectionString: process.env.DATABASE_URL });

async function findUserByToken(tokenHash) {
  const { rows } = await pool.query('SELECT * FROM users WHERE token_hash = $1', [tokenHash]);
  return rows[0] || null;
}

async function findBookingById(id) {
  const { rows } = await pool.query('SELECT * FROM bookings WHERE id = $1', [id]);
  return rows[0] || null;
}

async function findBookingsByGuest(guestEmail) {
  // FIXED: the email is passed as a parameter, not concatenated into the SQL
  const { rows } = await pool.query('SELECT * FROM bookings WHERE guest_email = $1', [guestEmail]);
  return rows;
}

async function findBookingsForRoom(roomId) {
  const { rows } = await pool.query('SELECT * FROM bookings WHERE room_id = $1', [roomId]);
  return rows;
}

async function insertBooking(b) {
  const { rows } = await pool.query(
    'INSERT INTO bookings (room_id, guest_email, check_in, check_out, total_cents) VALUES ($1, $2, $3, $4, $5) RETURNING *',
    [b.roomId, b.guestEmail, b.checkIn, b.checkOut, b.totalCents],
  );
  return rows[0];
}

async function deleteBooking(id) {
  await pool.query('DELETE FROM bookings WHERE id = $1', [id]);
}

module.exports = {
  findUserByToken, findBookingById, findBookingsByGuest, findBookingsForRoom, insertBooking, deleteBooking,
};
