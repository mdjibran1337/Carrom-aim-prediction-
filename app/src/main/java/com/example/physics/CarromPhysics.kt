package com.example.physics

import kotlin.math.*

object CarromPhysics {

    /**
     * Calculates the bounce (wall-reflection) path of a laser guide.
     * Returns a list of vertex points representing the primary guide line and successive bounces.
     */
    fun calculateBouncePath(
        startX: Float,
        startY: Float,
        dx: Float,
        dy: Float,
        xMin: Float,
        yMin: Float,
        xMax: Float,
        yMax: Float,
        maxBounces: Int = 3
    ): List<AimGuidePoint> {
        val points = mutableListOf<AimGuidePoint>()
        points.add(AimGuidePoint(startX, startY))

        var curX = startX
        var curY = startY
        var curDx = dx
        var curDy = dy

        // Normalize direction
        val mag = sqrt(curDx * curDx + curDy * curDy)
        if (mag < 0.0001f) return points
        curDx /= mag
        curDy /= mag

        for (bounce in 0..maxBounces) {
            var tMin = Float.MAX_VALUE
            var hitWall = -1 // 0: Left, 1: Right, 2: Top, 3: Bottom

            // Border intersection tests
            // Left wall: curX + t * curDx = xMin => t = (xMin - curX) / curDx
            if (curDx < -0.0001f) {
                val t = (xMin - curX) / curDx
                if (t > 0.05f && t < tMin) {
                    tMin = t
                    hitWall = 0
                }
            }
            // Right wall: curX + t * curDx = xMax => t = (xMax - curX) / curDx
            if (curDx > 0.0001f) {
                val t = (xMax - curX) / curDx
                if (t > 0.05f && t < tMin) {
                    tMin = t
                    hitWall = 1
                }
            }
            // Top wall: curY + t * curDy = yMin => t = (yMin - curY) / curDy
            if (curDy < -0.0001f) {
                val t = (yMin - curY) / curDy
                if (t > 0.05f && t < tMin) {
                    tMin = t
                    hitWall = 2
                }
            }
            // Bottom wall: curY + t * curDy = yMax => t = (yMax - curY) / curDy
            if (curDy > 0.0001f) {
                val t = (yMax - curY) / curDy
                if (t > 0.05f && t < tMin) {
                    tMin = t
                    hitWall = 3
                }
            }

            if (tMin == Float.MAX_VALUE || hitWall == -1) {
                // No wall hit in boundary limits (unlikely, but safe fallback)
                val extX = curX + curDx * 1000f
                val extY = curY + curDy * 1000f
                points.add(AimGuidePoint(extX, extY))
                break
            }

            // Move to hit point
            curX += curDx * tMin
            curY += curDy * tMin
            points.add(AimGuidePoint(curX, curY))

            // Reflect direction vector
            when (hitWall) {
                0, 1 -> curDx = -curDx // bounce off vertical wall
                2, 3 -> curDy = -curDy // bounce off horizontal wall
            }

            // Early exit if line has left screen range surprisingly
            if (curX < xMin - 5f || curX > xMax + 5f || curY < yMin - 5f || curY > yMax + 5f) {
                break
            }
        }
        return points
    }

    /**
     * Runs physics ticks for practice mode, handling:
     * - Circular item overlaps & velocity exchanges
     * - Wall reflection
     * - Board friction (deceleration)
     * - Pockets checking
     */
    fun updateSimulationTick(
        striker: CarromStriker,
        coins: List<CarromCoin>,
        pockets: List<Pocket>,
        width: Float,
        height: Float,
        xMin: Float,
        yMin: Float,
        xMax: Float,
        yMax: Float
    ) {
        val friction = 0.982f
        val stopPctSec = 0.15f
        val eRestitution = 0.85f // bounciness factor of coin collisions

        // 1. Move & decelerate Striker
        if (!striker.isPocketed) {
            striker.x += striker.vx
            striker.y += striker.vy
            striker.vx *= friction
            striker.vy *= friction
            if (sqrt(striker.vx * striker.vx + striker.vy * striker.vy) < stopPctSec) {
                striker.vx = 0f
                striker.vy = 0f
            }

            // Wall check for striker
            if (striker.x - striker.radius < xMin) {
                striker.x = xMin + striker.radius
                striker.vx = -striker.vx * eRestitution
            } else if (striker.x + striker.radius > xMax) {
                striker.x = xMax - striker.radius
                striker.vx = -striker.vx * eRestitution
            }

            if (striker.y - striker.radius < yMin) {
                striker.y = yMin + striker.radius
                striker.vy = -striker.vy * eRestitution
            } else if (striker.y + striker.radius > yMax) {
                striker.y = yMax - striker.radius
                striker.vy = -striker.vy * eRestitution
            }
        }

        // 2. Move & decelerate Coins
        for (coin in coins) {
            if (coin.isPocketed) continue
            coin.x += coin.vx
            coin.y += coin.vy
            coin.vx *= friction
            coin.vy *= friction
            if (sqrt(coin.vx * coin.vx + coin.vy * coin.vy) < stopPctSec) {
                coin.vx = 0f
                coin.vy = 0f
            }

            // Wall check for coins
            if (coin.x - coin.radius < xMin) {
                coin.x = xMin + coin.radius
                coin.vx = -coin.vx * eRestitution
            } else if (coin.x + coin.radius > xMax) {
                coin.x = xMax - coin.radius
                coin.vx = -coin.vx * eRestitution
            }

            if (coin.y - coin.radius < yMin) {
                coin.y = yMin + coin.radius
                coin.vy = -coin.vy * eRestitution
            } else if (coin.y + coin.radius > yMax) {
                coin.y = yMax - coin.radius
                coin.vy = -coin.vy * eRestitution
            }
        }

        // 3. Resolve Collisions: Striker vs. Coins
        if (!striker.isPocketed) {
            for (coin in coins) {
                if (coin.isPocketed) continue
                resolveCircleCollision(
                    x1 = striker.x, y1 = striker.y, r1 = striker.radius, w1 = striker.weight,
                    vx1 = striker.vx, vy1 = striker.vy,
                    x2 = coin.x, y2 = coin.y, r2 = coin.radius, w2 = coin.weight,
                    vx2 = coin.vx, vy2 = coin.vy,
                    onMatch = { nx, ny, v1x, v1y, v2x, v2y, ox, oy ->
                        striker.x -= nx * ox * 0.51f
                        striker.y -= ny * oy * 0.51f
                        coin.x += nx * ox * 0.51f
                        coin.y += ny * oy * 0.51f

                        striker.vx = v1x
                        striker.vy = v1y
                        coin.vx = v2x
                        coin.vy = v2y
                    }
                )
            }
        }

        // 4. Resolve Collisions: Coin vs. Coin
        for (i in coins.indices) {
            val c1 = coins[i]
            if (c1.isPocketed) continue
            for (j in i + 1 until coins.size) {
                val c2 = coins[j]
                if (c2.isPocketed) continue

                resolveCircleCollision(
                    x1 = c1.x, y1 = c1.y, r1 = c1.radius, w1 = c1.weight,
                    vx1 = c1.vx, vy1 = c1.vy,
                    x2 = c2.x, y2 = c2.y, r2 = c2.radius, w2 = c2.weight,
                    vx2 = c2.vx, vy2 = c2.vy,
                    onMatch = { nx, ny, v1x, v1y, v2x, v2y, ox, oy ->
                        c1.x -= nx * ox * 0.51f
                        c1.y -= ny * ox * 0.51f
                        c2.x += nx * ox * 0.51f
                        c2.y += ny * ox * 0.51f

                        c1.vx = v1x
                        c1.vy = v1y
                        c2.vx = v2x
                        c2.vy = v2y
                    }
                )
            }
        }

        // 5. Pocket Detection
        for (pocket in pockets) {
            // Striker checks
            if (!striker.isPocketed) {
                val dx = striker.x - pocket.x
                val dy = striker.y - pocket.y
                val dist = sqrt(dx * dx + dy * dy)
                if (dist < pocket.radius * 0.8f) {
                    striker.isPocketed = true
                    striker.vx = 0f
                    striker.vy = 0f
                }
            }

            // Coins check
            for (coin in coins) {
                if (coin.isPocketed) continue
                val dx = coin.x - pocket.x
                val dy = coin.y - pocket.y
                val dist = sqrt(dx * dx + dy * dy)
                if (dist < pocket.radius * 0.85f) {
                    coin.isPocketed = true
                    coin.vx = 0f
                    coin.vy = 0f
                }
            }
        }
    }

    /**
     * Core elastic circle collision math
     */
    private inline fun resolveCircleCollision(
        x1: Float, y1: Float, r1: Float, w1: Float, vx1: Float, vy1: Float,
        x2: Float, y2: Float, r2: Float, w2: Float, vx2: Float, vy2: Float,
        onMatch: (nx: Float, ny: Float, v1x: Float, v1y: Float, v2x: Float, v2y: Float, ox: Float, oy: Float) -> Unit
    ) {
        val dx = x2 - x1
        val dy = y2 - y1
        val dist = sqrt(dx * dx + dy * dy)
        val minDist = r1 + r2

        if (dist < minDist && dist > 0.001f) {
            val nx = dx / dist
            val ny = dy / dist
            val overlap = minDist - dist

            // Relative velocity
            val rvx = vx2 - vx1
            val rvy = vy2 - vy1

            // Velocity along normal
            val velAlongNormal = rvx * nx + rvy * ny

            // Only resolve if they are moving towards each other
            if (velAlongNormal < 0f) {
                val e = 0.85f
                val j = -(1f + e) * velAlongNormal / (1f / w1 + 1f / w2)

                // Exchange velocity impulses
                val v1x_new = vx1 - j * nx / w1
                val v1y_new = vy1 - j * ny / w1
                val v2x_new = vx2 + j * nx / w2
                val v2y_new = vy2 + j * ny / w2

                onMatch(nx, ny, v1x_new, v1y_new, v2x_new, v2y_new, overlap, overlap)
            }
        }
    }

    /**
     * Calculates the AI Suggestion Shot.
     * Searches for any clear shot to knock a coin into a pocket.
     */
    fun calculateAISuggestion(
        coins: List<CarromCoin>,
        strikerRadius: Float,
        baselineY: Float,
        pockets: List<Pocket>,
        xMin: Float, yMin: Float, xMax: Float, yMax: Float,
        forcedStrikerX: Float? = null
    ): AIShotSuggestion? {
        var bestShot: AIShotSuggestion? = null
        var maxScore = -1000f

        // If forcedStrikerX is set, only evaluate that exact position; otherwise, list candidate placements on the baseline
        val strikerPlacements = if (forcedStrikerX != null) {
            listOf(forcedStrikerX)
        } else {
            listOf(
                0.15f, 0.25f, 0.35f, 0.45f, 0.5f, 0.55f, 0.65f, 0.75f, 0.85f
            ).map { xMin + strikerRadius + 20f + it * (xMax - xMin - 2 * strikerRadius - 40f) }
        }

        for (strikerX in strikerPlacements) {
            val strX = strikerX
            val strY = baselineY

            for (coin in coins) {
                if (coin.isPocketed) continue

                for (pocketIdx in pockets.indices) {
                    val pocket = pockets[pocketIdx]

                    // ==========================================
                    // 1. PATH CONFIGURATION A: DIRECT SHOT
                    // ==========================================
                    run {
                        val pcX = coin.x - pocket.x
                        val pcY = coin.y - pocket.y
                        val pcDist = sqrt(pcX * pcX + pcY * pcY)
                        if (pcDist >= 50f) {
                            val pcDx = pcX / pcDist
                            val pcDy = pcY / pcDist

                            // Contact point T on the opposite side of the coin relative to the pocket
                            val targetX = coin.x + pcDx * (coin.radius + strikerRadius - 1.5f)
                            val targetY = coin.y + pcDy * (coin.radius + strikerRadius - 1.5f)

                            val stX = targetX - strX
                            val stY = targetY - strY
                            val stDist = sqrt(stX * stX + stY * stY)

                            if (stDist >= 40f) {
                                val stDx = stX / stDist
                                val stDy = stY / stDist

                                // Trajectory alignment cut angle
                                val coinDirX = -pcDx
                                val coinDirY = -pcDy
                                val dot = stDx * coinDirX + stDy * coinDirY

                                if (dot > 0.15f) {
                                    // Check obstructions
                                    var obstructed = false
                                    for (other in coins) {
                                        if (other.id == coin.id || other.isPocketed) continue

                                        if (distToSegment(other.x, other.y, strX, strY, targetX, targetY) < (other.radius + strikerRadius - 1.5f)) {
                                            obstructed = true
                                            break
                                        }
                                        if (distToSegment(other.x, other.y, coin.x, coin.y, pocket.x, pocket.y) < (other.radius + coin.radius - 1.5f)) {
                                            obstructed = true
                                            break
                                        }
                                    }

                                    if (!obstructed) {
                                        val expectedStrikerPath = listOf(
                                            AimGuidePoint(strX, strY),
                                            AimGuidePoint(targetX, targetY)
                                        )
                                        val expectedCoinPath = listOf(
                                            AimGuidePoint(coin.x, coin.y),
                                            AimGuidePoint(pocket.x, pocket.y)
                                        )

                                        val alignmentBonus = dot * 450f
                                        val distancePenalty = pcDist * 0.12f
                                        val strikerDistancePenalty = stDist * 0.04f
                                        val queenBonus = if (coin.isRed) 90f else if (coin.isWhite) 40f else 10f

                                        val score = alignmentBonus - distancePenalty - strikerDistancePenalty + queenBonus

                                        if (score > maxScore) {
                                            maxScore = score
                                            val angleRad = atan2(stY, stX)
                                            val angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()

                                            bestShot = AIShotSuggestion(
                                                strikerX = strikerX,
                                                targetAngle = angleDeg,
                                                power = min(1.0f, max(0.35f, (stDist / 600f) + 0.3f)),
                                                coinId = coin.id,
                                                pocketIndex = pocketIdx,
                                                expectedCoinPath = expectedCoinPath,
                                                expectedStrikerPath = expectedStrikerPath,
                                                score = score
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ==========================================
                    // 2. PATH CONFIGURATION B: COIN BANK SHOT (1-bounce of coin off wall)
                    // ==========================================
                    val walls = listOf(
                        Pair(0, xMin),  // Left
                        Pair(1, xMax),  // Right
                        Pair(2, yMin),  // Top
                        Pair(3, yMax)   // Bottom
                    )

                    for ((wallType, wallVal) in walls) {
                        val mirrorPocketX = when (wallType) {
                            0 -> 2 * xMin - pocket.x
                            1 -> 2 * xMax - pocket.x
                            else -> pocket.x
                        }
                        val mirrorPocketY = when (wallType) {
                            2 -> 2 * yMin - pocket.y
                            3 -> 2 * yMax - pocket.y
                            else -> pocket.y
                        }

                        val cToP_X = mirrorPocketX - coin.x
                        val cToP_Y = mirrorPocketY - coin.y
                        val cToP_dist = sqrt(cToP_X * cToP_X + cToP_Y * cToP_Y)
                        if (cToP_dist < 20f) continue

                        val cpDx = cToP_X / cToP_dist
                        val cpDy = cToP_Y / cToP_dist

                        var ix = 0f
                        var iy = 0f
                        var validIntersection = false

                        if (wallType == 0 || wallType == 1) { // Vertical wall hit
                            if (abs(cpDx) > 0.0001f) {
                                val t = (wallVal - coin.x) / cpDx
                                if (t > 0.05f) {
                                    ix = wallVal
                                    iy = coin.y + t * cpDy
                                    if (iy in yMin..yMax) {
                                        validIntersection = true
                                    }
                                }
                            }
                        } else { // Horizontal wall hit
                            if (abs(cpDy) > 0.0001f) {
                                val t = (wallVal - coin.y) / cpDy
                                if (t > 0.05f) {
                                    ix = coin.x + t * cpDx
                                    iy = wallVal
                                    if (ix in xMin..xMax) {
                                        validIntersection = true
                                    }
                                }
                            }
                        }

                        if (validIntersection) {
                            val ciX = ix - coin.x
                            val ciY = iy - coin.y
                            val ciDist = sqrt(ciX * ciX + ciY * ciY)
                            if (ciDist >= 10f) {
                                val coinDirX = ciX / ciDist
                                val coinDirY = ciY / ciDist

                                val targetX = coin.x - coinDirX * (coin.radius + strikerRadius - 1.5f)
                                val targetY = coin.y - coinDirY * (coin.radius + strikerRadius - 1.5f)

                                if (targetX in (xMin + strikerRadius)..(xMax - strikerRadius) &&
                                    targetY in (yMin + strikerRadius)..(yMax - strikerRadius)) {

                                    val stX = targetX - strX
                                    val stY = targetY - strY
                                    val stDist = sqrt(stX * stX + stY * stY)

                                    if (stDist >= 40f) {
                                        val stDx = stX / stDist
                                        val stDy = stY / stDist

                                        val dot = stDx * coinDirX + stDy * coinDirY

                                        if (dot > 0.25f) {
                                            var obstructed = false
                                            for (other in coins) {
                                                if (other.id == coin.id || other.isPocketed) continue

                                                if (distToSegment(other.x, other.y, strX, strY, targetX, targetY) < (other.radius + strikerRadius - 1.5f)) {
                                                    obstructed = true
                                                    break
                                                }
                                                if (distToSegment(other.x, other.y, coin.x, coin.y, ix, iy) < (other.radius + coin.radius - 1.5f)) {
                                                    obstructed = true
                                                    break
                                                }
                                                if (distToSegment(other.x, other.y, ix, iy, pocket.x, pocket.y) < (other.radius + coin.radius - 1.5f)) {
                                                    obstructed = true
                                                    break
                                                }
                                            }

                                            if (!obstructed) {
                                                val expectedStrikerPath = listOf(
                                                    AimGuidePoint(strX, strY),
                                                    AimGuidePoint(targetX, targetY)
                                                )
                                                val expectedCoinPath = listOf(
                                                    AimGuidePoint(coin.x, coin.y),
                                                    AimGuidePoint(ix, iy),
                                                    AimGuidePoint(pocket.x, pocket.y)
                                                )

                                                val totalCoinDistance = ciDist + sqrt((pocket.x - ix) * (pocket.x - ix) + (pocket.y - iy) * (pocket.y - iy))
                                                val alignmentBonus = dot * 380f
                                                val distancePenalty = totalCoinDistance * 0.15f
                                                val strikerDistancePenalty = stDist * 0.05f
                                                val cushionComplexityPenalty = 30f
                                                val queenBonus = if (coin.isRed) 90f else if (coin.isWhite) 40f else 10f

                                                val score = alignmentBonus - distancePenalty - strikerDistancePenalty - cushionComplexityPenalty + queenBonus

                                                if (score > maxScore) {
                                                    maxScore = score
                                                    val angleRad = atan2(stY, stX)
                                                    val angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()

                                                    bestShot = AIShotSuggestion(
                                                        strikerX = strikerX,
                                                        targetAngle = angleDeg,
                                                        power = min(1.0f, max(0.55f, (stDist / 600f) + 0.45f)),
                                                        coinId = coin.id,
                                                        pocketIndex = pocketIdx,
                                                        expectedCoinPath = expectedCoinPath,
                                                        expectedStrikerPath = expectedStrikerPath,
                                                        score = score
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ==========================================
                    // 3. PATH CONFIGURATION C: STRIKER CUSHION REBOUND
                    // ==========================================
                    val pcX = coin.x - pocket.x
                    val pcY = coin.y - pocket.y
                    val pcDist = sqrt(pcX * pcX + pcY * pcY)
                    if (pcDist >= 50f) {
                        val pcDx = pcX / pcDist
                        val pcDy = pcY / pcDist

                        val targetX = coin.x + pcDx * (coin.radius + strikerRadius - 1.5f)
                        val targetY = coin.y + pcDy * (coin.radius + strikerRadius - 1.5f)

                        val strikerWalls = listOf(
                            Pair(0, xMin),  // Left Wall
                            Pair(1, xMax),  // Right Wall
                            Pair(2, yMin)   // Top Wall (Opposite)
                        )

                        for ((wallType, wallVal) in strikerWalls) {
                            val mirrorTargetX = when (wallType) {
                                0 -> 2 * xMin - targetX
                                1 -> 2 * xMax - targetX
                                else -> targetX
                            }
                            val mirrorTargetY = when (wallType) {
                                2 -> 2 * yMin - targetY
                                else -> targetY
                            }

                            val sToT_X = mirrorTargetX - strX
                            val sToT_Y = mirrorTargetY - strY
                            val sToT_dist = sqrt(sToT_X * sToT_X + sToT_Y * sToT_Y)
                            if (sToT_dist >= 20f) {
                                val stDx = sToT_X / sToT_dist
                                val stDy = sToT_Y / sToT_dist

                                var ix = 0f
                                var iy = 0f
                                var validIntersection = false

                                if (wallType == 0 || wallType == 1) { // Vertical Wall
                                    if (abs(stDx) > 0.0001f) {
                                        val t = (wallVal - strX) / stDx
                                        if (t > 0.05f) {
                                            ix = wallVal
                                            iy = strY + t * stDy
                                            if (iy in yMin..yMax) {
                                                validIntersection = true
                                            }
                                        }
                                    }
                                } else { // Top Horizontal Wall
                                    if (abs(stDy) > 0.0001f) {
                                        val t = (wallVal - strY) / stDy
                                        if (t > 0.05f) {
                                            ix = strX + t * stDx
                                            iy = wallVal
                                            if (ix in xMin..xMax) {
                                                validIntersection = true
                                            }
                                        }
                                    }
                                }

                                if (validIntersection) {
                                    val isToTX = targetX - ix
                                    val isToTY = targetY - iy
                                    val isToTdist = sqrt(isToTX * isToTX + isToTY * isToTY)
                                    if (isToTdist >= 10f) {
                                        val colDirX = isToTX / isToTdist
                                        val colDirY = isToTY / isToTdist

                                        val dot = colDirX * (-pcDx) + colDirY * (-pcDy)

                                        if (dot > 0.25f) {
                                            var obstructed = false
                                            for (other in coins) {
                                                if (other.id == coin.id || other.isPocketed) continue

                                                if (distToSegment(other.x, other.y, strX, strY, ix, iy) < (other.radius + strikerRadius - 1.5f)) {
                                                    obstructed = true
                                                    break
                                                }
                                                if (distToSegment(other.x, other.y, ix, iy, targetX, targetY) < (other.radius + strikerRadius - 1.5f)) {
                                                    obstructed = true
                                                    break
                                                }
                                                if (distToSegment(other.x, other.y, coin.x, coin.y, pocket.x, pocket.y) < (other.radius + coin.radius - 1.5f)) {
                                                    obstructed = true
                                                    break
                                                }
                                            }

                                            if (!obstructed) {
                                                val expectedStrikerPath = listOf(
                                                    AimGuidePoint(strX, strY),
                                                    AimGuidePoint(ix, iy),
                                                    AimGuidePoint(targetX, targetY)
                                                )
                                                val expectedCoinPath = listOf(
                                                    AimGuidePoint(coin.x, coin.y),
                                                    AimGuidePoint(pocket.x, pocket.y)
                                                )

                                                val totalStrikerDistance = sqrt((ix - strX) * (ix - strX) + (iy - strY) * (iy - strY)) + isToTdist
                                                val alignmentBonus = dot * 380f
                                                val distancePenalty = pcDist * 0.12f
                                                val strikerDistancePenalty = totalStrikerDistance * 0.06f
                                                val strikerCushionPenalty = 40f
                                                val queenBonus = if (coin.isRed) 90f else if (coin.isWhite) 40f else 10f

                                                val score = alignmentBonus - distancePenalty - strikerDistancePenalty - strikerCushionPenalty + queenBonus

                                                if (score > maxScore) {
                                                    maxScore = score
                                                    val angleRad = atan2(iy - strY, ix - strX)
                                                    val angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()

                                                    bestShot = AIShotSuggestion(
                                                        strikerX = strikerX,
                                                        targetAngle = angleDeg,
                                                        power = min(1.0f, max(0.6f, (totalStrikerDistance / 600f) + 0.45f)),
                                                        coinId = coin.id,
                                                        pocketIndex = pocketIdx,
                                                        expectedCoinPath = expectedCoinPath,
                                                        expectedStrikerPath = expectedStrikerPath,
                                                        score = score
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return bestShot
    }

    /**
     * Distance from point (px, py) to line segment (x1, y1) -> (x2, y2)
     */
    private fun distToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val l2 = dx * dx + dy * dy
        if (l2 == 0f) return sqrt((px - x1) * (px - x1) + (py - y1) * (py - y1))

        var t = ((px - x1) * dx + (py - y1) * dy) / l2
        t = max(0f, min(1f, t))

        val projX = x1 + t * dx
        val projY = y1 + t * dy
        return sqrt((px - projX) * (px - projX) + (py - projY) * (py - projY))
    }
}
