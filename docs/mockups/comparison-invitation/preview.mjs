import { copyFile, mkdir, readFile, writeFile } from 'node:fs/promises';
import { openSync, closeSync } from 'node:fs';
import { spawn } from 'node:child_process';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/** Prepare a self-contained Superpowers companion session from this worktree.
 * The production page is read, never modified; all served assets are local.
 * Session output is ignored, while the editable design study is kept in docs.
 */
class ComparisonPreview {
  constructor(server) {
    this.server = resolve(server);
    this.study = dirname(fileURLToPath(import.meta.url));
    this.root = resolve(this.study, '../../..');
    this.session = join(this.root, '.superpowers/brainstorm', `comparison-invitation-${Date.now()}`);
    this.content = join(this.session, 'content');
  }

  async start() {
    await readFile(this.server); // Fail before preparing a session if the skill is unavailable.
    await mkdir(this.content, { recursive: true });
    const source = (await readFile(join(this.root, 'index.html'), 'utf8'))
      .replaceAll('./src/', '/files/')
      .replaceAll('./brand/', '/files/');
    await writeFile(join(this.content, 'website-source.txt'), source);
    for (const name of ['styles.css', 'illustrations.css', 'comparison.css']) {
      await copyFile(join(this.root, 'src', name), join(this.content, name));
    }
    for (const name of ['icon.png', 'wordmark.png', 'wordmark-dark.png']) {
      await copyFile(join(this.root, 'public/brand', name), join(this.content, name));
    }
    // Only the studio is an HTML file at the session root. The companion serves
    // its newest HTML screen; the source document must not compete with it.
    await copyFile(join(this.study, 'studio.html'), join(this.content, 'comparison-invitations.html'));
    const log = openSync(join(this.session, 'server.log'), 'a');
    const child = spawn(process.execPath, [this.server], {
      cwd: this.root,
      env: { ...process.env, BRAINSTORM_DIR: this.session, BRAINSTORM_HOST: '127.0.0.1', BRAINSTORM_URL_HOST: '127.0.0.1', BRAINSTORM_OWNER_PID: '' },
      detached: true,
      stdio: ['ignore', log, log],
      windowsHide: true,
    });
    closeSync(log);
    let spawnError;
    child.on('error', error => { spawnError = error; });
    child.unref();
    for (let attempt = 0; attempt < 50; attempt++) {
      if (spawnError) throw spawnError;
      try {
        const info = JSON.parse(await readFile(join(this.session, 'state/server-info'), 'utf8'));
        console.log(JSON.stringify({ ...info, pid: child.pid }));
        return;
      } catch (error) {
        if (error.code !== 'ENOENT' && !(error instanceof SyntaxError)) throw error;
      }
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    child.kill();
    throw new Error(`Companion did not start. Inspect ${join(this.session, 'server.log')}`);
  }
}

if (!process.argv[2]) {
  console.error('Usage: node docs/mockups/comparison-invitation/preview.mjs <superpowers-brainstorming/scripts/server.cjs>');
  process.exitCode = 1;
} else {
  await new ComparisonPreview(process.argv[2]).start();
}
