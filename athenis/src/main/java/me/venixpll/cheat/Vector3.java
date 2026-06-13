package me.venixpll.cheat;

/**
 * Vector3 represents a 3D coordinate point (x, y, z) in game space.
 * Used for storing locations of players, camera, and bounding box math.
 */
public class Vector3 {
    public float x;
    public float y;
    public float z;

    /**
     * Constructs a Vector3 with components set to 0.
     */
    public Vector3() {
        this.x = 0;
        this.y = 0;
        this.z = 0;
    }

    /**
     * Constructs a Vector3 with specified components.
     * @param x X coordinate (typically horizontal forward/backward)
     * @param y Y coordinate (typically horizontal left/right)
     * @param z Z coordinate (typically vertical height)
     */
    public Vector3(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /**
     * Adds another vector to this vector.
     * @param other The vector to add.
     * @return A new Vector3 representing the sum.
     */
    public Vector3 add(Vector3 other) {
        return new Vector3(this.x + other.x, this.y + other.y, this.z + other.z);
    }

    /**
     * Subtracts another vector from this vector.
     * @param other The vector to subtract.
     * @return A new Vector3 representing the difference.
     */
    public Vector3 subtract(Vector3 other) {
        return new Vector3(this.x - other.x, this.y - other.y, this.z - other.z);
    }

    /**
     * Calculates the distance between this vector and another.
     * @param other The other vector.
     * @return The 3D distance.
     */
    public float distance(Vector3 other) {
        float dx = this.x - other.x;
        float dy = this.y - other.y;
        float dz = this.z - other.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    public String toString() {
        return String.format("Vector3(%.2f, %.2f, %.2f)", x, y, z);
    }

    /**
     * Calculates the dot product of this vector and another.
     * @param other The other vector.
     * @return The dot product.
     */
    public float dot(Vector3 other) {
        return this.x * other.x + this.y * other.y + this.z * other.z;
    }

    /**
     * Calculates the cross product of this vector and another.
     * @param other The other vector.
     * @return A new Vector3 representing the cross product.
     */
    public Vector3 cross(Vector3 other) {
        return new Vector3(
            this.y * other.z - this.z * other.y,
            this.z * other.x - this.x * other.z,
            this.x * other.y - this.y * other.x
        );
    }

    /**
     * Calculates the squared length of this vector.
     * @return The squared length.
     */
    public float lengthSquared() {
        return this.x * this.x + this.y * this.y + this.z * this.z;
    }
}

