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

router.delete('/bookings/:id', async (req, res) => {
  await db.deleteBooking(req.params.id);
  res.status(204).end();
});

module.exports = router;
