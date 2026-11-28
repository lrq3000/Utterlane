import { DemoIllustrations } from './demos';
import { MotionPreference, MotionVisibility, ViewportReveals } from './motion';
import { ScrollStory } from './scroll-story';
import { ThemePreference } from './theme';
import { PaceComparison } from './pace';
import { showLatestRelease } from './release';

// Content, links and disclosures live in HTML and remain useful if this module
// never loads. Preferences and decorative demonstrations are enhancements.
new ThemePreference();
new DemoIllustrations();
const motion = new MotionPreference();
new MotionVisibility();
new ViewportReveals(motion);
new ScrollStory(motion);
new PaceComparison(motion);
const releaseDetail = document.querySelector<HTMLElement>('[data-release-detail]');
if (releaseDetail) void showLatestRelease(releaseDetail);
