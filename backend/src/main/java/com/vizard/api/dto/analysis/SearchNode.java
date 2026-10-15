package com.vizard.api.dto.analysis;

/**
 * One node of a state-space tree.
 *
 * @param id          position in the tree's node list
 * @param parent      the parent's id, or null for a first call
 * @param level       depth in the tree (the first call is 0)
 * @param label       short text for the node: the choice (2), or +3 / −3 for take / leave
 * @param choice      the choice in words: "col = 2", "take a[i] = 3"
 * @param firstStep   step at which the call starts (or the choice is rejected)
 * @param endStep     last step during which the call is running; -1 if it never returned
 * @param kind        backtracking: "explored" (had children), "solution", "dead-end" (a leaf that is no
 *                    solution), "pruned" (stopped by a bound check), "rejected" (the check said no; never called);
 *                    recursion trees: "computed", "base" (answered directly), "hit" (answered from the memo),
 *                    "repeat" (plain recursion: these arguments were already computed once);
 *                    branch and bound with a live-node list: "expanded", "pruned" (its bound could not beat
 *                    the best), "live" (still in the queue when the search ended)
 * @param kindStep    step from which the kind is known (a solution as soon as it is recorded,
 *                    a dead end once its call returns)
 * @param liveStep    branch and bound with a live-node list: step it entered the queue, or null
 * @param pollStep    step it was taken out of the queue to be expanded (or discarded), or null
 */
public record SearchNode(int id, Integer parent, int level, String label, String choice, int firstStep, int endStep,
                         String kind, int kindStep, Integer liveStep, Integer pollStep) {

    public SearchNode(int id, Integer parent, int level, String label, String choice, int firstStep, int endStep,
                      String kind, int kindStep) {
        this(id, parent, level, label, choice, firstStep, endStep, kind, kindStep, null, null);
    }
}
