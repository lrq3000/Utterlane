import { defineConfig } from 'vite';

// Relative assets keep the same artifact usable under /Utterlane/, a renamed
// repository, or a custom Pages domain without rebuilding the site's content.
export default defineConfig({ base: './' });
