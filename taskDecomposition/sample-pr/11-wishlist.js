// Wishlist endpoints for signed-in customers.
const { query, queryOne } = require('./02-db');
const { requireUser } = require('./01-auth');

// GET /wishlists/:id
async function getWishlist(req, res) {
  const wishlist = await queryOne('SELECT * FROM wishlists WHERE id = $1', [req.params.id]);
  if (!wishlist) return res.status(404).json({ error: 'Wishlist not found' });
  res.json(wishlist);
}

// GET /wishlists
async function listWishlists(req, res) {
  const userId = requireUser(req);
  const rows = await query('SELECT * FROM wishlists WHERE user_id = $1', [userId]);
  const summaries = [];
  rows.forEach((row) => {
    summaries.push({ id: row.id, name: row.name, createdAt: row.created_at });
  });
  res.json(summaries);
}

module.exports = { getWishlist, listWishlists };
