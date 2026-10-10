package com.vizard.execution;

import com.vizard.api.dto.trace.EntrySnapshot;
import com.vizard.api.dto.trace.HeapObjectSnapshot;
import com.vizard.api.dto.trace.TraceResponse;
import com.vizard.api.dto.trace.TraceStep;
import com.vizard.api.dto.trace.ValueSnapshot;
import com.vizard.api.dto.trace.VariableSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 5: collection contents are read from the JDK's internal fields (nothing runs in the
 * program), and each collection's role comes from how the program used it.
 */
@SpringBootTest
class CollectionsTest {

    @Autowired
    private ExecutionService service;

    private static final String PROGRAM = """
            import java.util.*;

            public class Main {
                static class Node { int data; Node next; Node(int d) { data = d; } }
                static class TNode { int key; TNode left, right; TNode(int k) { key = k; } }

                public static void main(String[] args) {
                    ArrayList<Integer> list = new ArrayList<>(List.of(3, 1, 2));
                    ArrayDeque<Integer> dq = new ArrayDeque<>(4);
                    for (int i = 0; i < 6; i++) dq.offer(i);
                    dq.poll(); dq.poll(); dq.offer(9);
                    Deque<Character> st = new ArrayDeque<>();
                    st.push('('); st.push('[');
                    Stack<Integer> old = new Stack<>();
                    old.push(1); old.push(2);
                    Queue<String> q = new LinkedList<>();
                    q.offer("a"); q.offer("b"); q.poll();
                    PriorityQueue<Integer> pq = new PriorityQueue<>(List.of(5, 1, 4, 2));
                    Map<String, Integer> hm = new HashMap<>();
                    hm.put("x", 1); hm.put("x", 3);
                    Map<Integer, String> lhm = new LinkedHashMap<>();
                    lhm.put(30, "c"); lhm.put(10, "a"); lhm.put(20, "b");
                    TreeMap<Integer, String> tm = new TreeMap<>(lhm);
                    TreeSet<Integer> ts = new TreeSet<>(List.of(5, 3, 8));
                    List<Integer> fixed = List.of(7, 8);
                    Node head = new Node(1);
                    head.next = new Node(2);
                    TNode root = new TNode(5);
                    root.left = new TNode(3);
                    System.out.println("done");
                }
            }
            """;

    private static HeapObjectSnapshot object(TraceStep step, String variable) {
        VariableSnapshot v = step.stack().get(0).variables().stream()
                .filter(x -> x.name().equals(variable)).findFirst().orElseThrow();
        return step.heap().get(v.value().ref().toString());
    }

    private static List<String> elements(HeapObjectSnapshot o) {
        return o.elements().stream().map(ValueSnapshot::display).toList();
    }

    private static List<String> entries(HeapObjectSnapshot o) {
        return o.entries().stream().map((EntrySnapshot e) -> e.key().display() + "=" + e.value().display()).toList();
    }

    @Test
    void contentsAndRolesOfEveryCollection() {
        TraceResponse r = service.trace(PROGRAM, "");
        TraceStep last = r.steps().get(r.steps().size() - 1);

        assertThat(elements(object(last, "list"))).containsExactly("3", "1", "2");
        // a circular buffer that wrapped around while growing, read in queue order
        assertThat(elements(object(last, "dq"))).containsExactly("2", "3", "4", "5", "9");
        assertThat(object(last, "dq").role()).isEqualTo("queue");

        HeapObjectSnapshot st = object(last, "st");
        assertThat(st.role()).isEqualTo("stack");
        assertThat(st.top()).isEqualTo("first");                 // Deque.push adds at the front
        assertThat(elements(st)).containsExactly("'['", "'('");

        HeapObjectSnapshot old = object(last, "old");
        assertThat(old.role()).isEqualTo("stack");
        assertThat(old.top()).isEqualTo("last");                 // java.util.Stack pushes at the end
        assertThat(elements(old)).containsExactly("1", "2");

        assertThat(object(last, "q").role()).isEqualTo("queue");   // a LinkedList used through Queue
        assertThat(elements(object(last, "q"))).containsExactly("\"b\"");
        assertThat(object(last, "pq").role()).isEqualTo("priority-queue");
        assertThat(elements(object(last, "pq"))).containsExactly("1", "2", "4", "5"); // heap array order

        assertThat(entries(object(last, "hm"))).containsExactly("\"x\"=3");
        assertThat(entries(object(last, "lhm"))).containsExactly("30=\"c\"", "10=\"a\"", "20=\"b\"");
        assertThat(entries(object(last, "tm"))).containsExactly("10=\"a\"", "20=\"b\"", "30=\"c\"");
        assertThat(elements(object(last, "ts"))).containsExactly("3", "5", "8");
        assertThat(elements(object(last, "fixed"))).containsExactly("7", "8");
    }

    @Test
    void userNodesAreClassifiedByTheirLinks() {
        List<TraceStep> steps = service.trace(PROGRAM, "").steps();
        TraceStep last = steps.get(steps.size() - 1);
        assertThat(object(last, "head").role()).isEqualTo("list-node");
        assertThat(object(last, "head").links()).containsExactly("next");
        assertThat(object(last, "root").role()).isEqualTo("tree-node");
        assertThat(object(last, "root").links()).containsExactly("left", "right");
    }

    @Test
    void operationsAreAttachedWhereTheirEffectIsVisible() {
        TraceResponse r = service.trace(PROGRAM, "");
        TraceStep afterPushes = r.steps().stream()
                .filter(s -> s.operations().stream().anyMatch(op -> op.method().equals("push") && op.type().equals("ArrayDeque")))
                .findFirst().orElseThrow();
        assertThat(afterPushes.operations()).extracting(op -> op.kind()).contains("push");
        assertThat(elements(object(afterPushes, "st"))).isNotEmpty(); // the push has already happened in this snapshot
    }
}
