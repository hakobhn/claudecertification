// The hotel's rooms and their nightly rates.
const ROOMS = {
  101: { type: 'single', nightlyCents: 8900 },
  102: { type: 'double', nightlyCents: 12900 },
  201: { type: 'suite', nightlyCents: 24900 },
};

function getRoom(roomId) {
  const room = ROOMS[roomId];
  if (!room) throw new Error(`Unknown room ${roomId}`);
  return room;
}

function listRooms() {
  return Object.entries(ROOMS).map(([id, room]) => ({ id: Number(id), ...room }));
}

module.exports = { getRoom, listRooms };
