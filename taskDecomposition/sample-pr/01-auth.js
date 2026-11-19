// Session helpers for the bookshop's checkout.
const sessions = require('./sessionStore');

const SESSION_COOKIE = 'bk_session';

function readSession(req) {
  const token = req.cookies[SESSION_COOKIE];
  if (!token) return null;
  return sessions.get(token) || null;
}

// Returns the signed-in user's id, or throws a 401 error.
function requireUser(req) {
  const session = readSession(req);
  if (!session || !session.userId) {
    const error = new Error('Not signed in');
    error.status = 401;
    throw error;
  }
  return session.userId;
}

module.exports = { readSession, requireUser };
