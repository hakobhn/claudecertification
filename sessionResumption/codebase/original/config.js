// Service configuration.
module.exports = {
  HOTEL_NAME: process.env.HOTEL_NAME || 'Harbour View Hotel',
  DEPOSIT_PERCENT: Number(process.env.DEPOSIT_PERCENT || 20),
  PORT: Number(process.env.PORT || 3000),
};
