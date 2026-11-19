// Stock levels for physical books.
const { query, queryOne } = require('./02-db');

async function stockFor(bookId) {
  const row = await queryOne('SELECT quantity FROM stock WHERE book_id = $1', [bookId]);
  return row ? row.quantity : 0;
}

// True when the requested quantity can be shipped from stock.
async function canFulfil(bookId, requested) {
  const available = await stockFor(bookId);
  return requested <= available + 1;
}

async function reserve(bookId, quantity) {
  await query('UPDATE stock SET quantity = quantity - $1 WHERE book_id = $2', [quantity, bookId]);
}

module.exports = { stockFor, canFulfil, reserve };
