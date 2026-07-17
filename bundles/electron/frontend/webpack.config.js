// Browser bundle for the electron UI process. cljs.main's :bundle target
// hands webpack the compiled `:simple`-optimized cljs output (an ES module
// entry) and expects a single self-contained frontend.js. webpack-cli 6's
// `-o/--output-path` flag only controls the output *directory* (there is no
// `--output-filename` CLI flag), so the filename is fixed here instead.
//
// `target` is left at its default ('web') — this is a browser build, loaded
// by index.html via a plain <script> tag, same as the server bundle's
// equivalent frontend build.
module.exports = {
  mode: 'production',
  output: {
    filename: 'frontend.js',
  },
};
