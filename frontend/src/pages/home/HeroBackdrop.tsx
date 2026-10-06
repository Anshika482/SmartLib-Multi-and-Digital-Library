import avif800 from '@/assets/library-hall-800.avif';
import avif1280 from '@/assets/library-hall-1280.avif';
import avif1920 from '@/assets/library-hall-1920.avif';
import webp800 from '@/assets/library-hall-800.webp';
import webp1280 from '@/assets/library-hall-1280.webp';
import webp1920 from '@/assets/library-hall-1920.webp';

const AVIF = `${avif800} 800w, ${avif1280} 1280w, ${avif1920} 1920w`;
const WEBP = `${webp800} 800w, ${webp1280} 1280w, ${webp1920} 1920w`;

/**
 * The reading room behind the hero.
 *
 * <p>A photograph of a real library hall, with the drawn scene below it as a
 * fallback. The drawing came first and reads well on its own, but under an
 * overlay heavy enough for text it flattened to a dark smudge - a photograph
 * has the tonal range to stay legible as a room under the same scrim.</p>
 *
 * <p>The picture is mirrored in CSS so its dense shelving falls on the side of
 * the hero the text does not occupy, and its darker corridor sits behind the
 * words. Three widths in AVIF and WebP; the browser takes one.</p>
 *
 * <p>Entirely decorative: the image has an empty alt, the SVG is hidden from
 * assistive technology, and neither carries information that is not also in
 * the text beside them. If the photograph fails to load, the drawing is simply
 * what shows.</p>
 */
export function HeroBackdrop() {
  // Spine positions and heights, fixed rather than random: a backdrop that
  // reshuffled itself on every render would shimmer during navigation.
  const shelves = [
    { y: 152, spines: [3, 5, 2, 6, 4, 3, 5, 2, 4, 6, 3, 5, 4, 2, 5, 3] },
    { y: 196, spines: [5, 2, 4, 3, 6, 2, 5, 4, 3, 5, 2, 6, 3, 4, 2, 5] },
    { y: 240, spines: [2, 4, 6, 3, 5, 4, 2, 6, 5, 3, 4, 2, 5, 6, 3, 4] },
  ];

  return (
    <>
      <picture>
        <source type="image/avif" srcSet={AVIF} sizes="100vw" />
        <source type="image/webp" srcSet={WEBP} sizes="100vw" />
        <img
          className="sl-hero__photo"
          src={webp1280}
          alt=""
          decoding="async"
          fetchPriority="high"
        />
      </picture>

      <svg
        className="sl-hero__art"
      viewBox="0 0 1200 420"
      preserveAspectRatio="xMidYMax slice"
      aria-hidden="true"
      focusable="false"
    >
      <defs>
        <linearGradient id="sl-hero-sky" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#18223d" />
          <stop offset="100%" stopColor="#070b16" />
        </linearGradient>

        <radialGradient id="sl-hero-lamp" cx="50%" cy="50%" r="50%">
          <stop offset="0%" stopColor="#ffd9a0" stopOpacity="0.55" />
          <stop offset="55%" stopColor="#f6c177" stopOpacity="0.14" />
          <stop offset="100%" stopColor="#f6c177" stopOpacity="0" />
        </radialGradient>

        <radialGradient id="sl-hero-window" cx="50%" cy="30%" r="70%">
          <stop offset="0%" stopColor="#9db8ff" stopOpacity="0.35" />
          <stop offset="100%" stopColor="#6c8cff" stopOpacity="0.04" />
        </radialGradient>

        <linearGradient id="sl-hero-fade" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#070b16" stopOpacity="0" />
          <stop offset="70%" stopColor="#070b16" stopOpacity="0.72" />
          <stop offset="100%" stopColor="#070b16" stopOpacity="0.96" />
        </linearGradient>
      </defs>

      <rect width="1200" height="420" fill="url(#sl-hero-sky)" />

      {/* The window at the far end of the hall. */}
      <path
        d="M520 300V150a80 80 0 0 1 160 0v150Z"
        fill="url(#sl-hero-window)"
      />
      <path
        d="M520 300V150a80 80 0 0 1 160 0v150Z"
        fill="none"
        stroke="#9db8ff"
        strokeOpacity="0.22"
        strokeWidth="1.5"
      />
      <path d="M600 72v228M520 190h160" stroke="#9db8ff" strokeOpacity="0.16" strokeWidth="1.5" />

      {/* Shelving, three ranks, lighter as they recede. */}
      {shelves.map((shelf, rank) => (
        <g key={shelf.y} opacity={0.5 + rank * 0.18}>
          <rect x="0" y={shelf.y + 34} width="1200" height="2" fill="#2d3a5c" opacity="0.7" />
          {shelf.spines.map((weight, index) => {
            // Mirrored around the centre so the hall reads as symmetrical.
            const slot = index * 75 + (rank % 2 === 0 ? 8 : 26);
            const height = 16 + weight * 3.4;
            return (
              <rect
                key={`${shelf.y}-${slot}`}
                x={slot}
                y={shelf.y + 34 - height}
                width={weight * 2.6 + 5}
                height={height}
                rx="1.5"
                fill={index % 3 === 0 ? '#2d3a5c' : '#212d4c'}
              />
            );
          })}
        </g>
      ))}

      {/* Lamps, and the pools of light they throw. */}
      {[240, 600, 960].map((x, index) => (
        <g key={x}>
          <ellipse cx={x} cy={120} rx={180} ry={120} fill="url(#sl-hero-lamp)" />
          <path d={`M${x} 0v46`} stroke="#2d3a5c" strokeWidth="1.5" />
          <path
            d={`M${x - 22} 46h44l-10 22h-24Z`}
            fill="#111a30"
            stroke="#e0a458"
            strokeOpacity="0.4"
            strokeWidth="1"
          />
          <circle cx={x} cy={70} r="3" fill="#ffd9a0" opacity={index === 1 ? 0.95 : 0.7} />
        </g>
      ))}

      {/* The floor fades to the page so the artwork has no hard edge. */}
      <rect y="220" width="1200" height="200" fill="url(#sl-hero-fade)" />
      </svg>
    </>
  );
}
