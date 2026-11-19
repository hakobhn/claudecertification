// HTTP routes of the room booking service.
const express = require('express');
const { authenticate } = require('./auth');
const db = require('./database');
const { createBooking } = require('./bookings');

const router = express.Router();

router.get('/bookings', async (req, res) => {
  const user = await authenticate(req);
  if (!user) return res.status(401).end();
  res.json(await db.findBookingsByGuest(user.email));
});

router.post('/bookings', async (req, res) => {
  const user = await authenticate(req);
  if (!user) return res.status(401).end();
  try {
    res.status(201).json(await createBooking(user, req.body));
  } catch (err) {
    res.status(409).json({ error: err.message });
  }
});

// FIXED: only the signed-in guest who owns a booking can cancel it
router.delete('/bookings/:id', async (req, res) => {
  const user = await authenticate(req);
  if (!user) return res.status(401).end();
  const booking = await db.findBookingById(req.params.id);
  if (!booking || booking.guest_email !== user.email) return res.status(404).end();
  await db.deleteBooking(booking.id);
  res.status(204).end();
});

module.exports = router;
