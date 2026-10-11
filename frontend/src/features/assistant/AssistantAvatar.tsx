import { cn } from '@/lib/cn';

type Motion = 'none' | 'idle' | 'talk';

/**
 * The SmartLib AI assistant's round avatar, used in the chat header, next to replies, and on the home page.
 * motion: 'idle' floats gently with a breathing glow, 'talk' pulses while a reply is being written. Both are skipped
 * for people who ask their device for reduced motion.
 */
export function AssistantAvatar({ className, decorative, motion = 'none' }: { className?: string; decorative?: boolean; motion?: Motion }) {
  return (
    <span className={cn('group inline-flex shrink-0 rounded-full shadow-[0_0_24px_-6px_rgba(57,197,255,0.55)]',
      motion === 'idle' && 'motion-safe:animate-avatar-idle', motion === 'talk' && 'motion-safe:animate-avatar-talk', className)}>
      <img src="/brand/assistant-avatar.webp" alt={decorative ? '' : 'SmartLib AI assistant'} draggable={false}
        className="size-full select-none rounded-full object-cover transition-transform duration-300 ease-out motion-safe:group-hover:scale-110" />
    </span>
  );
}
