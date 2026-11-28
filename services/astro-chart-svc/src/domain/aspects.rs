//! Ptolemaic aspects between chart points (natal and transit).

use super::angles::angle_diff;
use super::body::Body;

/// A chart point: a body, or one of the two angles. The order is the fixed output order.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Point {
    Body(Body),
    Asc,
    MC,
}

impl Point {
    /// Position in the fixed order: the bodies, then `Asc`, `MC`.
    pub fn order(self) -> usize {
        match self {
            Point::Body(b) => b as usize,
            Point::Asc => Body::ALL.len(),
            Point::MC => Body::ALL.len() + 1,
        }
    }

    /// The stable ASCII wire key.
    pub fn key(self) -> &'static str {
        match self {
            Point::Body(b) => b.key(),
            Point::Asc => "Asc",
            Point::MC => "MC",
        }
    }

    fn is_angle(self) -> bool {
        matches!(self, Point::Asc | Point::MC)
    }

    fn is_luminary(self) -> bool {
        matches!(self, Point::Body(Body::Sun | Body::Moon))
    }

    fn is_node(self) -> bool {
        matches!(self, Point::Body(Body::NorthNode))
    }

    fn is_minor(self) -> bool {
        matches!(self, Point::Body(Body::NorthNode | Body::Chiron))
    }
}

/// The five Ptolemaic aspects.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AspectKind {
    Conjunction,
    Opposition,
    Trine,
    Square,
    Sextile,
}

impl AspectKind {
    const ALL: [AspectKind; 5] = [
        AspectKind::Conjunction,
        AspectKind::Opposition,
        AspectKind::Trine,
        AspectKind::Square,
        AspectKind::Sextile,
    ];

    /// The exact separation, in degrees.
    pub fn angle(self) -> f64 {
        match self {
            AspectKind::Conjunction => 0.0,
            AspectKind::Opposition => 180.0,
            AspectKind::Trine => 120.0,
            AspectKind::Square => 90.0,
            AspectKind::Sextile => 60.0,
        }
    }

    /// The stable ASCII wire key.
    pub fn key(self) -> &'static str {
        match self {
            AspectKind::Conjunction => "conjunction",
            AspectKind::Opposition => "opposition",
            AspectKind::Trine => "trine",
            AspectKind::Square => "square",
            AspectKind::Sextile => "sextile",
        }
    }

    fn natal_orb(self) -> f64 {
        match self {
            AspectKind::Conjunction | AspectKind::Opposition | AspectKind::Trine => 8.0,
            AspectKind::Square => 7.0,
            AspectKind::Sextile => 6.0,
        }
    }
}

/// Longitude and speed (degrees per day) of a point.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct PointPos {
    pub point: Point,
    pub lon: f64,
    pub speed: f64,
}

/// An aspect found between `a` and `b`; `orb` is |separation − exact| in degrees.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Aspect {
    pub a: Point,
    pub b: Point,
    pub kind: AspectKind,
    pub orb: f64,
    pub applying: bool,
}

/// Flat orb of an angle aspect.
const ANGLE_ORB: f64 = 5.0;
/// Flat orb of a pair containing the node or Chiron.
const MINOR_ORB: f64 = 3.0;
/// Extra orb of a pair containing the Sun or the Moon.
const LUMINARY_BONUS: f64 = 2.0;
/// Orb of a transiting Moon.
const TRANSIT_MOON_ORB: f64 = 3.0;
/// Orb of the other transiting bodies.
const TRANSIT_ORB: f64 = 2.0;

/// The orb allowed for `kind` between two natal points, `None` when the pair never has it.
fn natal_allowed_orb(p: Point, q: Point, kind: AspectKind) -> Option<f64> {
    if p.is_angle() || q.is_angle() {
        // Angles aspect only the ten planets, never each other, the node or Chiron.
        return if (p.is_angle() && q.is_angle()) || p.is_minor() || q.is_minor() {
            None
        } else {
            Some(ANGLE_ORB)
        };
    }
    if p.is_minor() || q.is_minor() {
        let axis_only = p.is_node() || q.is_node();
        let allowed =
            !axis_only || matches!(kind, AspectKind::Conjunction | AspectKind::Opposition);
        return allowed.then_some(MINOR_ORB);
    }
    let bonus = if p.is_luminary() || q.is_luminary() {
        LUMINARY_BONUS
    } else {
        0.0
    };
    Some(kind.natal_orb() + bonus)
}

/// The closest aspect between `a` and `b` within `orb_of(kind)`, if any.
fn best_aspect(
    a: PointPos,
    b: PointPos,
    orb_of: impl Fn(AspectKind) -> Option<f64>,
) -> Option<Aspect> {
    let d = angle_diff(a.lon, b.lon);
    let sep = d.abs();
    let mut best: Option<Aspect> = None;
    for kind in AspectKind::ALL {
        let Some(max) = orb_of(kind) else { continue };
        let dev = sep - kind.angle();
        let orb = dev.abs();
        if orb > max || best.is_some_and(|x| x.orb <= orb) {
            continue;
        }
        // d(separation)/dt, then the sign of d(|dev|)/dt: applying when the deviation shrinks.
        let sep_rate = if d >= 0.0 { 1.0 } else { -1.0 } * (a.speed - b.speed);
        let applying = orb > 0.0 && dev.signum() * sep_rate < 0.0;
        best = Some(Aspect {
            a: a.point,
            b: b.point,
            kind,
            orb,
            applying,
        });
    }
    best
}

/// Natal aspects between all pairs, each pair at most once, ordered by `(a, b)`.
pub fn natal_aspects(points: &[PointPos]) -> Vec<Aspect> {
    let mut sorted = points.to_vec();
    sorted.sort_by_key(|p| p.point.order());
    let mut out = Vec::new();
    for (i, a) in sorted.iter().enumerate() {
        for b in &sorted[i + 1..] {
            if let Some(asp) = best_aspect(*a, *b, |k| natal_allowed_orb(a.point, b.point, k)) {
                out.push(asp);
            }
        }
    }
    out
}

/// Aspects of transiting points (`a`) to natal points (`b`), at the transit orbs.
pub fn transit_aspects(transiting: &[PointPos], natal: &[PointPos]) -> Vec<Aspect> {
    let mut out = Vec::new();
    for t in transiting {
        let orb = if t.point == Point::Body(Body::Moon) {
            TRANSIT_MOON_ORB
        } else {
            TRANSIT_ORB
        };
        for n in natal {
            if let Some(asp) = best_aspect(*t, *n, |_| Some(orb)) {
                out.push(asp);
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn p(b: Body, lon: f64) -> PointPos {
        PointPos {
            point: Point::Body(b),
            lon,
            speed: 0.0,
        }
    }

    fn angle(pt: Point, lon: f64) -> PointPos {
        PointPos {
            point: pt,
            lon,
            speed: 0.0,
        }
    }

    fn moving(b: Body, lon: f64, speed: f64) -> PointPos {
        PointPos { speed, ..p(b, lon) }
    }

    fn one(points: &[PointPos]) -> Option<Aspect> {
        let v = natal_aspects(points);
        assert!(v.len() <= 1);
        v.first().copied()
    }

    #[test]
    fn luminary_gets_two_extra_degrees() {
        let a = one(&[p(Body::Sun, 10.0), p(Body::Mars, 19.5)]).unwrap();
        assert_eq!(a.kind, AspectKind::Conjunction);
        assert!((a.orb - 9.5).abs() < 1e-9);
        assert!(one(&[p(Body::Sun, 10.0), p(Body::Mars, 20.5)]).is_none());
    }

    #[test]
    fn square_orb_is_seven_plus_two_for_the_moon() {
        let a = one(&[p(Body::Sun, 10.0), p(Body::Moon, 107.5)]).unwrap();
        assert_eq!(a.kind, AspectKind::Square);
        assert!((a.orb - 7.5).abs() < 1e-9);
        assert_eq!(
            (a.a, a.b),
            (Point::Body(Body::Sun), Point::Body(Body::Moon))
        );
    }

    #[test]
    fn plain_planets_use_eight_degrees_for_a_conjunction() {
        let a = one(&[p(Body::Venus, 0.0), p(Body::Mars, 7.9)]).unwrap();
        assert_eq!(a.kind, AspectKind::Conjunction);
        assert!(one(&[p(Body::Venus, 0.0), p(Body::Mars, 8.1)]).is_none());
    }

    #[test]
    fn conjunction_wraps_across_zero_aries() {
        let a = one(&[p(Body::Sun, 359.95), p(Body::Moon, 0.05)]).unwrap();
        assert_eq!(a.kind, AspectKind::Conjunction);
        assert!((a.orb - 0.1).abs() < 1e-9);
    }

    #[test]
    fn node_takes_only_conjunction_and_opposition_at_three_degrees() {
        let a = one(&[p(Body::NorthNode, 10.0), p(Body::Venus, 12.9)]).unwrap();
        assert_eq!(a.kind, AspectKind::Conjunction);
        assert!(one(&[p(Body::NorthNode, 10.0), p(Body::Venus, 100.0)]).is_none());
        let o = one(&[p(Body::NorthNode, 10.0), p(Body::Venus, 189.0)]).unwrap();
        assert_eq!(o.kind, AspectKind::Opposition);
    }

    #[test]
    fn chiron_is_flat_three_without_the_moon_bonus() {
        let a = one(&[p(Body::Chiron, 10.0), p(Body::Moon, 72.9)]).unwrap();
        assert_eq!(a.kind, AspectKind::Sextile);
        assert_eq!(
            (a.a, a.b),
            (Point::Body(Body::Moon), Point::Body(Body::Chiron))
        );
        assert!(one(&[p(Body::Chiron, 10.0), p(Body::Moon, 74.0)]).is_none());
    }

    #[test]
    fn angles_aspect_planets_at_five_flat() {
        let a = one(&[angle(Point::Asc, 0.0), p(Body::Venus, 94.9)]).unwrap();
        assert_eq!(a.kind, AspectKind::Square);
        assert_eq!((a.a, a.b), (Point::Body(Body::Venus), Point::Asc));
        assert!(one(&[angle(Point::Asc, 0.0), p(Body::Venus, 95.1)]).is_none());
        // The Sun's bonus does not apply to an angle.
        assert!(one(&[angle(Point::MC, 0.0), p(Body::Sun, 6.0)]).is_none());
    }

    #[test]
    fn angles_never_aspect_each_other_the_node_or_chiron() {
        assert!(one(&[angle(Point::Asc, 0.0), angle(Point::MC, 0.0)]).is_none());
        assert!(one(&[angle(Point::Asc, 0.0), p(Body::NorthNode, 0.0)]).is_none());
        assert!(one(&[angle(Point::MC, 10.0), p(Body::Chiron, 10.0)]).is_none());
    }

    #[test]
    fn applying_when_the_gap_to_exact_shrinks() {
        let venus = moving(Body::Venus, 0.0, 1.2);
        let mars = moving(Body::Mars, 7.0, 0.5);
        assert!(one(&[venus, mars]).unwrap().applying);
        let venus = moving(Body::Venus, 7.0, 1.2);
        let mars = moving(Body::Mars, 0.0, 0.5);
        let a = one(&[venus, mars]).unwrap();
        assert!(!a.applying);
        // Order is by the fixed order, whatever the input order.
        assert_eq!(a.a, Point::Body(Body::Venus));
    }

    #[test]
    fn applying_square_and_opposition_follow_the_exact_angle() {
        let sun = p(Body::Sun, 0.0);
        // Separation 85 growing towards 90: applying.
        assert!(one(&[sun, moving(Body::Mars, 85.0, 1.0)]).unwrap().applying);
        // Separation 175 growing towards 180: applying; shrinking: separating.
        assert!(
            one(&[sun, moving(Body::Mars, 175.0, 1.0)])
                .unwrap()
                .applying
        );
        assert!(
            !one(&[sun, moving(Body::Mars, 175.0, -1.0)])
                .unwrap()
                .applying
        );
    }

    #[test]
    fn an_exact_aspect_is_not_applying() {
        let a = one(&[p(Body::Sun, 10.0), moving(Body::Mars, 10.0, 1.0)]).unwrap();
        assert!(!a.applying);
    }

    #[test]
    fn results_are_sorted_by_fixed_order() {
        let v = natal_aspects(&[p(Body::Mars, 5.0), p(Body::Sun, 0.0), p(Body::Venus, 2.0)]);
        let order: Vec<_> = v.iter().map(|x| (x.a.order(), x.b.order())).collect();
        assert_eq!(order, vec![(0, 3), (0, 4), (3, 4)]);
    }

    #[test]
    fn transit_orbs_are_two_and_three_for_the_moon() {
        let natal = [p(Body::Sun, 100.0)];
        let v = transit_aspects(&[p(Body::Mars, 101.9)], &natal);
        assert_eq!(v.len(), 1);
        assert_eq!(
            (v[0].a, v[0].b),
            (Point::Body(Body::Mars), Point::Body(Body::Sun))
        );
        assert_eq!(transit_aspects(&[p(Body::Mars, 102.1)], &natal).len(), 0);
        assert_eq!(transit_aspects(&[p(Body::Moon, 102.9)], &natal).len(), 1);
        assert_eq!(transit_aspects(&[p(Body::Moon, 103.1)], &natal).len(), 0);
    }

    #[test]
    fn keys_are_stable() {
        assert_eq!(Point::Asc.key(), "Asc");
        assert_eq!(Point::MC.key(), "MC");
        assert_eq!(Point::Body(Body::NorthNode).key(), "NorthNode");
        assert_eq!(AspectKind::Sextile.key(), "sextile");
        assert_eq!(Point::MC.order(), 13);
    }
}
