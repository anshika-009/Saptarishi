/** Seven dots in the shape of the Big Dipper: the seven sages. */
export default function Logo({ size = 28 }) {
  return (
    <svg
      width={size}
      height={(size * 22) / 30}
      viewBox="0 0 30 22"
      role="img"
      aria-label="Saptarishi"
    >
      <polyline
        points="3,17 8,14 13,13 18,12 20,5 27,6 26,13 18,12"
        fill="none"
        stroke="var(--ink)"
        strokeWidth="1"
        opacity=".5"
      />
      <g fill="var(--ink)">
        <circle cx="3" cy="17" r="2" />
        <circle cx="8" cy="14" r="2" />
        <circle cx="13" cy="13" r="2" />
        <circle cx="18" cy="12" r="2.4" />
        <circle cx="20" cy="5" r="2" />
        <circle cx="27" cy="6" r="2" />
        <circle cx="26" cy="13" r="2" />
      </g>
    </svg>
  );
}
