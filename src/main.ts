import { DemoIllustrations } from './demos';
import { MotionPreference, MotionVisibility, ViewportReveals } from './motion';
import { ScrollStory } from './scroll-story';

// Content, links and disclosures live in HTML and remain useful if this module
// never loads. Only the decorative product demonstrations need enhancement.
new DemoIllustrations();
const motion = new MotionPreference();
new MotionVisibility();
new ViewportReveals(motion);
new ScrollStory(motion);
