//----------------------------------------------------------------------------//
//                                                                            //
//                              S i g S o l v e r                             //
//                                                                            //
//----------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//  Copyright © Herve Bitteur and others 2000-2013. All rights reserved.
//  This software is released under the GNU General Public License.
//  Goto http://kenai.com/projects/audiveris to report bugs or suggestions.
//----------------------------------------------------------------------------//
// </editor-fold>
package omr.sig;

import omr.glyph.Shape;
import omr.glyph.ShapeSet;
import omr.glyph.facets.Glyph;

import omr.grid.StaffInfo;

import omr.math.GeoOrder;
import omr.math.GeoUtil;

import omr.sheet.SystemInfo;

import omr.util.HorizontalSide;
import static omr.util.HorizontalSide.*;
import omr.util.Navigable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.SortedSet;

/**
 * Class {@code SigSolver} deals with SIG resolution.
 *
 * @author Hervé Bitteur
 */
public class SigSolver
{
    //~ Static fields/initializers ---------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(
            SigSolver.class);

    //~ Instance fields --------------------------------------------------------
    /** The dedicated system */
    @Navigable(false)
    private final SystemInfo system;

    /** The related SIG. */
    private final SIGraph sig;

    //~ Constructors -----------------------------------------------------------
    //-----------//
    // SigSolver //
    //-----------//
    /**
     * Creates a new SigSolver object.
     *
     * @param system the related system
     * @param sig    the system SIG
     */
    public SigSolver (SystemInfo system,
                      SIGraph sig)
    {
        this.system = system;
        this.sig = sig;
    }

    //~ Methods ----------------------------------------------------------------
    //-------//
    // solve //
    //-------//
    /**
     * Reduce the interpretations and relations of the SIG.
     */
    public void solve ()
    {
        logger.debug("S#{} solving sig ...", system.getId());

        /** Count of modifications done in current iteration. */
        int modifs;

        do {
            modifs = 0;
            // Detect lack of mandatory support relation for certain inters
            modifs += checkHeads();
            modifs += checkBeams();
            modifs += checkHooks();
            modifs += checkStems();
            modifs += checkLedgers();
            logger.debug("S#{} modifs: {}", system.getId(), modifs);
        } while (modifs > 0);
    }

    //------------------//
    // beamHasBothStems //
    //------------------//
    /**
     * Check if a beam has a stem at both ends.
     */
    private boolean beamHasBothStems (FullBeamInter beam)
    {
        boolean hasLeft = false;
        boolean hasRight = false;

        if (beam.isVip()) {
            logger.info("VIP beamHasBothStems for {}", beam);
        }

        for (Relation rel : sig.edgesOf(beam)) {
            if (rel instanceof BeamStemRelation) {
                BeamStemRelation bsRel = (BeamStemRelation) rel;
                BeamPortion portion = bsRel.getBeamPortion();

                if (portion == BeamPortion.LEFT) {
                    hasLeft = true;
                } else if (portion == BeamPortion.RIGHT) {
                    hasRight = true;
                }
            }
        }

        return hasLeft && hasRight;
    }

    //-------------//
    // hookHasStem //
    //-------------//
    /**
     * Check if a beam hook has a stem.
     */
    private boolean hookHasStem (BeamHookInter hook)
    {
        boolean hasLeft = false;
        boolean hasRight = false;

        if (hook.isVip()) {
            logger.info("VIP hookHasStem for {}", hook);
        }

        for (Relation rel : sig.edgesOf(hook)) {
            if (rel instanceof BeamStemRelation) {
                BeamStemRelation bsRel = (BeamStemRelation) rel;
                BeamPortion portion = bsRel.getBeamPortion();

                if (portion == BeamPortion.LEFT) {
                    hasLeft = true;
                } else if (portion == BeamPortion.RIGHT) {
                    hasRight = true;
                }
            }
        }

        return hasLeft || hasRight;
    }

    //------------//
    // checkBeams //
    //------------//
    /**
     * Perform checks on beams.
     *
     * @return the count of modifications done
     */
    private int checkBeams ()
    {
        int modifs = 0;
        List<Inter> beams = sig.inters(FullBeamInter.class);

        for (Iterator<Inter> it = beams.iterator(); it.hasNext();) {
            FullBeamInter beam = (FullBeamInter) it.next();

            if (!beamHasBothStems(beam)) {
                if (beam.isVip() || logger.isDebugEnabled()) {
                    logger.info("Deleting beam lacking stem {}", beam);
                }

                sig.removeVertex(beam);
                it.remove();
                modifs++;
            }
        }

        return modifs;
    }

    //------------//
    // checkHooks //
    //------------//
    /**
     * Perform checks on beam hooks.
     *
     * @return the count of modifications done
     */
    private int checkHooks ()
    {
        int modifs = 0;
        List<Inter> hooks = sig.inters(BeamHookInter.class);

        for (Iterator<Inter> it = hooks.iterator(); it.hasNext();) {
            BeamHookInter hook = (BeamHookInter) it.next();

            if (!hookHasStem(hook)) {
                if (hook.isVip() || logger.isDebugEnabled()) {
                    logger.info("Deleting beam hook lacking stem {}", hook);
                }

                sig.removeVertex(hook);
                it.remove();
                modifs++;
            }
        }

        return modifs;
    }

    //---------------//
    // checkHeadSide //
    //---------------//
    /**
     * If head is on the wrong side of the stem, check if there is a
     * head on the other side one step further.
     *
     * @param head the head inter (black or void)
     * @return the number of modifications done
     */
    private int checkHeadSide (Inter head)
    {
        int modifs = 0;

        // Check all connected stems
        List<HeadStemRelation> stemRels = new ArrayList<HeadStemRelation>();

        for (Relation rel : sig.edgesOf(head)) {
            if (rel instanceof HeadStemRelation) {
                stemRels.add((HeadStemRelation) rel);
            }
        }

        for (HeadStemRelation rel : stemRels) {
            StemInter stem = (StemInter) sig.getEdgeTarget(rel);

            // What is the stem direction? (up: dir < 0, down: dir > 0)
            Integer dir = stemDirection(stem);

            if (dir == null) {
                continue; // Since we can't decide
            }

            // Side is normal?
            HorizontalSide headSide = rel.getHeadSide();

            if (((headSide == LEFT) && (dir > 0))
                || ((headSide == RIGHT) && (dir < 0))) {
                continue; // It's OK
            }

            // Pitch of the head
            int pitch = (head instanceof BlackHeadInter)
                    ? ((BlackHeadInter) head).getPitch()
                    : ((VoidHeadInter) head).getPitch();

            // Target side and pitch of other head
            HorizontalSide targetSide = (headSide == LEFT) ? RIGHT : LEFT;
            int targetPitch = pitch - dir;

            // Look for presence of head on other side with target pitch
            Inter otherHead = stem.lookupHead(targetSide, targetPitch);

            if (otherHead != null) {
                continue; // OK
            }

            // We have a bad head+stem couple, let's remove the relationship
            if (head.isVip() || logger.isDebugEnabled()) {
                logger.info("Incompatibility between {} and {}", head, stem);
            }

            sig.removeEdge(rel);
            modifs++;
        }

        return modifs;
    }

    //------------//
    // checkHeads //
    //------------//
    /**
     * Perform checks on heads.
     *
     * @return the count of modifications done
     */
    private int checkHeads ()
    {
        int modifs = 0;
        final List<Inter> heads = sig.inters(ShapeSet.NoteHeads.getShapes());

        for (Iterator<Inter> it = heads.iterator(); it.hasNext();) {
            final Inter head = it.next();

            if (head.isVip()) {
                logger.info("VIP checkHeads for {}", head);
            }

            if (!headHasStem(head)) {
                if (head.isVip() || logger.isDebugEnabled()) {
                    logger.info("Deleting head lacking stem {}", head);
                }

                sig.removeVertex(head);
                it.remove();
                modifs++;

                continue;
            }

            modifs += checkHeadSide(head);
        }

        return modifs;
    }

    //--------------//
    // checkLedgers //
    //--------------//
    /**
     * Perform checks on ledger.
     *
     * @return the count of modifications done
     */
    private int checkLedgers ()
    {
        int modifs = 0;
        boolean modified;

        do {
            modified = false;

            List<Inter> allNotes = sig.inters(
                    ShapeSet.shapesOf(
                            ShapeSet.NoteHeads.getShapes(),
                            Arrays.asList(Shape.WHOLE_NOTE)));
            Collections.sort(allNotes, Inter.byAbscissa);

            for (StaffInfo staff : system.getStaves()) {
                SortedMap<Integer, SortedSet<Glyph>> map = staff.getLedgerMap();

                for (Entry<Integer, SortedSet<Glyph>> entry : map.entrySet()) {
                    int index = entry.getKey();
                    SortedSet<Glyph> glyphs = entry.getValue();
                    List<LedgerInter> ledgers = ledgerIntersOf(glyphs);

                    for (LedgerInter ledger : ledgers) {
                        if (ledger.isVip()) {
                            logger.info("VIP ledger {}", ledger);
                        }

                        if (!ledgerHasNoteOrLedger(
                                staff,
                                index,
                                ledger,
                                allNotes)) {
                            if (ledger.isVip() || logger.isDebugEnabled()) {
                                logger.info(
                                        "Deleting orphan ledger {}",
                                        ledger);
                            }

                            sig.removeVertex(ledger);
                            modified = true;
                            modifs++;
                        }
                    }
                }
            }
        } while (modified);

        return modifs;
    }

    //------------//
    // checkStems //
    //------------//
    /**
     * Perform checks on stems.
     *
     * @return the count of modifications done
     */
    private int checkStems ()
    {
        int modifs = 0;
        List<Inter> stems = sig.inters(Shape.STEM);

        for (Iterator<Inter> it = stems.iterator(); it.hasNext();) {
            StemInter stem = (StemInter) it.next();

            if (!stemHasHeadAtStart(stem)) {
                if (stem.isVip() || logger.isDebugEnabled()) {
                    logger.info("Deleting stem lacking starting head {}", stem);
                }

                sig.removeVertex(stem);
                it.remove();
                modifs++;

                continue;
            }

            //            if (!stemHasSingleHeadEnd(stem)) {
            //                logger.warn("Deleting stem with head at both ends {}", stem);
            //                sig.removeVertex(stem);
            //                it.remove();
            //                modifs++;
            //
            //                continue;
            //            }
        }

        return modifs;
    }

    //-------------//
    // headHasStem //
    //-------------//
    /**
     * Check if the head has a stem relation.
     *
     * @param inter the head inter (black of void)
     * @return true if OK
     */
    private boolean headHasStem (Inter inter)
    {
        for (Relation rel : sig.edgesOf(inter)) {
            if (rel instanceof HeadStemRelation) {
                return true;
            }
        }

        return false;
    }

    //-----------------------//
    // ledgerHasNoteOrLedger //
    //-----------------------//
    /**
     * Check if the provided ledger has either a note centered on it
     * (or one step further) or another ledger right further.
     *
     * @param staff    the containing staff
     * @param index    the ledger line index
     * @param ledger   the ledger to check
     * @param allHeads the abscissa-ordered list of heads in the system
     * @return true if OK
     */
    private boolean ledgerHasNoteOrLedger (StaffInfo staff,
                                           int index,
                                           LedgerInter ledger,
                                           List<Inter> allHeads)
    {
        Rectangle ledgerBox = ledger.getBounds();

        // Check for another ledger on next line
        int nextIndex = index + Integer.signum(index);
        SortedSet<Glyph> nextGlyphs = staff.getLedgers(nextIndex);

        if (nextGlyphs != null) {
            List<LedgerInter> nextLedgers = ledgerIntersOf(nextGlyphs);

            for (LedgerInter nextLedger : nextLedgers) {
                // Check abscissa compatibility
                if (GeoUtil.xOverlap(ledgerBox, nextLedger.getBounds()) > 0) {
                    return true;
                }
            }
        }

        // Else, check for a note centered on ledger, or just on next pitch
        final int pitch = StaffInfo.getLedgerPitchPosition(index);
        final int nextPitch = pitch + Integer.signum(index);

        final List<Inter> heads = sig.intersectedInters(
                allHeads,
                GeoOrder.BY_ABSCISSA,
                ledgerBox);

        for (Inter head : heads) {
            final int headPitch = (head instanceof BlackHeadInter)
                    ? ((BlackHeadInter) head).getPitch()
                    : ((head instanceof VoidHeadInter)
                    ? ((VoidHeadInter) head).getPitch()
                    : ((WholeInter) head).getPitch());

            if ((headPitch == pitch) || (headPitch == nextPitch)) {
                return true;
            }
        }

        return false;
    }

    //----------------//
    // ledgerIntersOf //
    //----------------//
    /**
     * Retrieve the collection of ledgers interpretations that can be
     * found in the provided collection of glyphs.
     *
     * @param glyphs the provided glyph instances
     * @return the ledgers interpretations found
     */
    private List<LedgerInter> ledgerIntersOf (Collection<Glyph> glyphs)
    {
        List<LedgerInter> ledgers = new ArrayList<LedgerInter>();

        for (Glyph glyph : glyphs) {
            for (Inter inter : glyph.getInterpretations()) {
                if (inter instanceof LedgerInter) {
                    ledgers.add((LedgerInter) inter);
                }
            }
        }

        return ledgers;
    }

    //---------------//
    // stemDirection //
    //---------------//
    /**
     * Report the direction of the provided stem.
     * <p>
     * For this, we check what is found on each stem end (beam/flag or head).
     *
     * @param stem the stem to check
     * @return -1 for stem up, +1 for stem down, null for unknown
     */
    private Integer stemDirection (StemInter stem)
    {
        boolean up = false;
        boolean down = false;

        for (Relation rel : sig.edgesOf(stem)) {
            if (rel instanceof HeadStemRelation) {
                HeadStemRelation headStem = (HeadStemRelation) rel;
                StemPortion portion = headStem.getStemPortion();

                if (portion == StemPortion.STEM_BOTTOM) {
                    up = true;
                } else if (portion == StemPortion.STEM_TOP) {
                    down = true;
                }
            } else if (rel instanceof BeamStemRelation) {
                BeamStemRelation beamStem = (BeamStemRelation) rel;
                StemPortion portion = beamStem.getStemPortion();

                if (portion == StemPortion.STEM_BOTTOM) {
                    down = true;
                } else if (portion == StemPortion.STEM_TOP) {
                    up = true;
                }
            }

            //TODO: one day, check for flag?
        }

        if (up && down) {
            // Non consistent
            return null;
        }

        if (up) {
            return -1;
        }

        if (down) {
            return +1;
        }

        // No info
        return null;
    }

    //--------------------//
    // stemHasHeadAtStart //
    //--------------------//
    /**
     * Check if the stem has a head at one (starting) end.
     *
     * @param stem the stem inter
     * @return true if OK
     */
    private boolean stemHasHeadAtStart (StemInter stem)
    {
        for (Relation rel : sig.edgesOf(stem)) {
            if (rel instanceof HeadStemRelation) {
                HeadStemRelation hsRel = (HeadStemRelation) rel;

                // Check stem portion
                if (hsRel.getStemPortion() != StemPortion.STEM_MIDDLE) {
                    return true;
                }
            }
        }

        return false;
    }

    //----------------------//
    // stemHasSingleHeadEnd //
    //----------------------//
    /**
     * Check if the stem does not have one head at each end
     *
     * @param stem the stem inter
     * @return true if OK
     */
    private boolean stemHasSingleHeadEnd (StemInter stem)
    {
        boolean hasTop = false;
        boolean hasBottom = false;

        for (Relation rel : sig.edgesOf(stem)) {
            if (rel instanceof HeadStemRelation) {
                HeadStemRelation hsRel = (HeadStemRelation) rel;

                // Check stem portion
                StemPortion portion = hsRel.getStemPortion();

                if (portion == StemPortion.STEM_TOP) {
                    hasTop = true;
                } else if (portion == StemPortion.STEM_BOTTOM) {
                    hasBottom = true;
                }
            }
        }

        return !hasTop || !hasBottom;
    }
}