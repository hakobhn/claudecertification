// Guest authentication for the room booking service.
const crypto = require('crypto');
const { findUserByToken } = require('./database');

function hashToken(token) {
  return crypto.createHash('sha256').update(token).digest('hex');
}

// Returns the user for a bearer token, or null if the token is unknown or expired.
async function authenticate(req) {
  const header = req.headers.authorization || '';
  const token = header.replace(/^Bearer /, '');
  if (!token) return null;
  const user = await findUserByToken(hashToken(token));
  if (!user) return null;
  if (user.token_expires_at <= Date.now()) return null; // FIXED: expired tokens are rejected
  return user;
}

module.exports = { authenticate, hashToken };
