import { DemoIllustrations } from './demos';
import { MotionPreference, MotionVisibility, ViewportReveals } from './motion';
import { ScrollStory } from './scroll-story';
import { ThemePreference } from './theme';

// Content, links and disclosures live in HTML and remain useful if this module
// never loads. Preferences and decorative demonstrations are enhancements.
new ThemePreference();
new DemoIllustrations();
const motion = new MotionPreference();
new MotionVisibility();
new ViewportReveals(motion);
new ScrollStory(motion);
