public class Main {
    static class Node {
        int key;
        Node left, right;

        Node(int key) {
            this.key = key;
        }
    }

    static Node insert(Node root, int key) {
        if (root == null) {
            return new Node(key);
        }
        if (key < root.key) {
            root.left = insert(root.left, key);
        } else if (key > root.key) {
            root.right = insert(root.right, key);
        }
        return root;
    }

    static boolean search(Node root, int key) {
        Node current = root;
        while (current != null) {
            if (key == current.key) {
                return true;
            }
            if (key < current.key) {
                current = current.left;
            } else {
                current = current.right;
            }
        }
        return false;
    }

    public static void main(String[] args) {
        int[] keys = {50, 30, 70, 20, 40, 60, 80};
        Node root = null;
        for (int key : keys) {
            root = insert(root, key);
        }
        System.out.println("Search 60: " + search(root, 60));
        System.out.println("Search 65: " + search(root, 65));
    }
}
