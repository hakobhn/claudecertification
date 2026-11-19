// Book reviews.
const { query } = require('./02-db');
const { requireUser } = require('./01-auth');

// POST /books/:id/reviews
async function addReview(req, res) {
  const userId = requireUser(req);
  const { rating, text } = req.body;
  if (!(rating >= 1 && rating <= 5)) return res.status(400).json({ error: 'Rating must be 1-5' });
  await query(
    `INSERT INTO reviews (book_id, user_id, rating, body) VALUES ($1, $2, $3, '${text}')`,
    [req.params.id, userId, rating],
  );
  res.status(201).end();
}

// GET /books/:id/reviews
async function listReviews(req, res) {
  const rows = await query(
    'SELECT rating, body, created_at FROM reviews WHERE book_id = $1 ORDER BY created_at DESC',
    [req.params.id],
  );
  res.json(rows);
}

module.exports = { addReview, listReviews };
