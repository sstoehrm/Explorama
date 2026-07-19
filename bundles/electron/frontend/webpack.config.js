// Browser bundle for the electron UI process. webpack-cli 6's -o flag only
// sets the output directory (no --output-filename flag), so the frontend.js
// name index.html expects is fixed here.
module.exports = {
  mode: 'production',
  output: {
    filename: 'frontend.js',
  },
};
