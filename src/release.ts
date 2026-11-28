const latestReleaseApi = 'https://api.github.com/repos/lrq3000/Utterlane/releases/latest';

export async function showLatestRelease(detail: HTMLElement): Promise<void> {
  try {
    const response = await fetch(latestReleaseApi, {
      credentials: 'omit',
      referrerPolicy: 'no-referrer',
      signal: AbortSignal.timeout(5000),
    });
    if (!response.ok) return;

    const release: { tag_name?: unknown; published_at?: unknown } | null = await response.json();
    const tag = typeof release?.tag_name === 'string' ? release.tag_name.trim() : '';
    if (!tag) return;

    detail.textContent = `Latest release: ${tag}`;
    if (typeof release?.published_at === 'string') {
      const published = new Date(release.published_at);
      if (Number.isFinite(published.getTime())) {
        // Use GitHub's UTC publication day, even for visitors west of UTC around midnight.
        const time = document.createElement('time');
        time.dateTime = published.toISOString().slice(0, 10);
        time.textContent = new Intl.DateTimeFormat('en', { dateStyle: 'medium', timeZone: 'UTC' }).format(published);
        detail.append(' · Published ', time);
      }
    }
    detail.hidden = false;
  } catch {
    // Metadata is optional; the static download link works even if GitHub is unavailable.
  }
}
