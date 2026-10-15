import java.util.PriorityQueue;

public class Main {
    // Items sorted by value per unit of weight (needed for the fractional bound).
    static int[] w = {4, 7, 5, 3};
    static int[] p = {40, 42, 25, 12};
    static int W = 10;
    static int n = w.length;

    static class Node {
        int level;       // last item decided (-1 = none yet)
        int profit;
        int weight;
        double bound;    // the most this branch could still reach

        Node(int level, int profit, int weight) {
            this.level = level;
            this.profit = profit;
            this.weight = weight;
        }
    }

    // Upper bound: fill the rest greedily, taking a fraction of the first item that doesn't fit.
    static double bound(Node u) {
        if (u.weight > W) {
            return 0;
        }
        double result = u.profit;
        int totalWeight = u.weight;
        int j = u.level + 1;
        while (j < n && totalWeight + w[j] <= W) {
            totalWeight += w[j];
            result += p[j];
            j++;
        }
        if (j < n) {
            result += (W - totalWeight) * (double) p[j] / w[j];
        }
        return result;
    }

    // Best-first branch and bound: always expand the live node with the highest bound.
    static int knapsack() {
        PriorityQueue<Node> pq = new PriorityQueue<>((a, b) -> Double.compare(b.bound, a.bound));
        Node root = new Node(-1, 0, 0);
        root.bound = bound(root);
        pq.add(root);
        int best = 0;
        while (!pq.isEmpty()) {
            Node u = pq.poll();
            if (u.bound <= best || u.level == n - 1) {
                continue;                                    // can't beat the best: prune
            }
            int next = u.level + 1;
            Node take = new Node(next, u.profit + p[next], u.weight + w[next]);
            if (take.weight <= W && take.profit > best) {
                best = take.profit;                          // a better solution
            }
            take.bound = bound(take);
            if (take.bound > best) {
                pq.add(take);
            }
            Node leave = new Node(next, u.profit, u.weight);
            leave.bound = bound(leave);
            if (leave.bound > best) {
                pq.add(leave);
            }
        }
        return best;
    }

    public static void main(String[] args) {
        System.out.println("Best value: " + knapsack());
    }
}
