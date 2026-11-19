// Public catalogue endpoints. No sign-in needed.
const { query } = require('./02-db');

const PAGE_SIZE = 20;

// GET /books?q=...&page=...
async function searchBooks(req, res) {
  const term = req.query.q || '';
  const page = Math.max(1, parseInt(req.query.page, 10) || 1);
  const rows = await query(
    "SELECT id, title, author, price_cents FROM books WHERE title ILIKE '%" + term + "%' " +
      'ORDER BY title LIMIT $1 OFFSET $2',
    [PAGE_SIZE, (page - 1) * PAGE_SIZE],
  );
  res.json({ page, results: rows });
}

// GET /books/:id
async function getBook(req, res) {
  const rows = await query('SELECT * FROM books WHERE id = $1', [req.params.id]);
  if (rows.length === 0) return res.status(404).json({ error: 'Book not found' });
  res.json(rows[0]);
}

module.exports = { searchBooks, getBook };
