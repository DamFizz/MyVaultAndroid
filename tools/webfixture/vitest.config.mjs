// Runs with the web repo's own vitest (no node_modules here):
//   node <DAMMYVAULT>/node_modules/vitest/vitest.mjs run --config tools/webfixture/vitest.config.mjs <filter>
// MYVAULT_WEB_SRC overrides where the web app's src/ lives (default: sibling D:\DAMMYVAULT).
import path from 'node:path';

const here = import.meta.dirname;
const webSrc = process.env.MYVAULT_WEB_SRC ?? path.resolve(here, '../../../DAMMYVAULT/src');

export default {
  root: here,
  cacheDir: path.join(here, '.cache'),
  resolve: { alias: { '@web': webSrc } },
  test: {
    globals: true,
    environment: 'node',
    include: ['*.fixture.ts'],
    testTimeout: 180_000,
    fileParallelism: false,
  },
};
