public class Main {
    static final int N = 4;

    // Can a queen go on board[row][col]? Rows above are filled already,
    // so check the column and the two diagonals going up.
    static boolean isSafe(int[][] board, int row, int col) {
        for (int i = 0; i < row; i++) {
            if (board[i][col] == 1) return false;
        }
        for (int i = row - 1, j = col - 1; i >= 0 && j >= 0; i--, j--) {
            if (board[i][j] == 1) return false;
        }
        for (int i = row - 1, j = col + 1; i >= 0 && j < N; i--, j++) {
            if (board[i][j] == 1) return false;
        }
        return true;
    }

    // Place one queen per row; backtrack when a row has no safe column.
    static boolean solve(int[][] board, int row) {
        if (row == N) {
            return true;
        }
        for (int col = 0; col < N; col++) {
            if (isSafe(board, row, col)) {
                board[row][col] = 1;               // choose
                if (solve(board, row + 1)) {       // explore
                    return true;
                }
                board[row][col] = 0;               // undo (backtrack)
            }
        }
        return false;
    }

    public static void main(String[] args) {
        int[][] board = new int[N][N];
        if (solve(board, 0)) {
            for (int[] r : board) {
                StringBuilder line = new StringBuilder();
                for (int cell : r) {
                    line.append(cell == 1 ? "Q " : ". ");
                }
                System.out.println(line);
            }
        } else {
            System.out.println("No solution");
        }
    }
}
