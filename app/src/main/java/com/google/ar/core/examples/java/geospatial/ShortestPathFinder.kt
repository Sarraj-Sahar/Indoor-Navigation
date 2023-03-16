import java.util.*

class Node(
    val id: Int,
    val adjacent: MutableSet<Node>,
) {
    fun addEdge(node: Node) {
        this.adjacent.add(node)
        node.adjacent.add(this)
    }

    override fun toString(): String {
        return id.toString()
    }

}

fun shortestPath(source: Node, destination: Node): List<Node> {
    // Create a map to store the distance from the source node to each node
    val distances = mutableMapOf<Node, Int>()

    // Set the distance of the source node to 0
    distances[source] = 0

    // Create a set to store visited nodes
    val visited = mutableSetOf<Node>()

    // Create a map to store the previous node on the shortest path
    val previousNodes = mutableMapOf<Node, Node?>()

    // Initialize the previous node map with null values for all nodes except the source node
    previousNodes[source] = null

    // Create a priority queue to store nodes to be visited
    val queue = PriorityQueue<Node>(compareBy { distances.getOrDefault(it, Int.MAX_VALUE) })

    // Add the source node to the queue
    queue.offer(source)

    // Loop until the queue is empty or the destination node is found
    while (queue.isNotEmpty()) {
        // Remove the node with the shortest distance from the queue
        val current = queue.poll()

        // If the current node is the destination, return the shortest path
        if (current == destination) {
            val path = mutableListOf<Node>()
            var node: Node? = current
            while (node != null) {
                path.add(0, node)
                node = previousNodes[node]
            }
            return path
        }

        // Mark the current node as visited
        visited.add(current)

        // Iterate over the adjacent nodes
        for (adjacent in current.adjacent) {
            // If the adjacent node has not been visited, update its distance
            if (!visited.contains(adjacent)) {
                // Calculate the distance to the adjacent node as the distance to the current node plus one
                val distance = distances.getOrDefault(current, Int.MAX_VALUE) + 1

                // If the new distance is shorter than the previous distance, update the distance and previous node maps
                if (distance < distances.getOrDefault(adjacent, Int.MAX_VALUE)) {
                    distances[adjacent] = distance
                    previousNodes[adjacent] = current
                }

                // Add the adjacent node to the queue
                queue.offer(adjacent)
            }
        }
    }

    // If the destination node was not found, return an empty list to indicate that there is no path
    return emptyList()
}

fun main(args: Array<String>) {
    val node1 = Node(1, mutableSetOf())
    val node2 = Node(2, mutableSetOf())
    val node3 = Node(3, mutableSetOf())
    val node4 = Node(4, mutableSetOf())
    val node5 = Node(5, mutableSetOf())
    val node6 = Node(6, mutableSetOf())

    node1.addEdge(node2)
    node1.addEdge(node3)
    node1.addEdge(node4)

    node2.addEdge(node4)
    node2.addEdge(node5)

    node3.addEdge(node4)

    node3.addEdge(node5)

    node5.addEdge(node6)

    val nodes = mutableListOf<Node>(node1 , node2, node3, node4 , node5 , node6)

    val path = shortestPath(nodes[0], nodes[5])
    println("Shortest path from source to destination: $path")
}
