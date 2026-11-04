package com.calvinmazhindu.skitcam.project

enum class ProjectState { WaitingDestination, Preparing, Writing, Verifying, Finalising, Completed, Failed, Cancelled, Interrupted }
val ProjectState.active get()=this in setOf(ProjectState.Preparing,ProjectState.Writing,ProjectState.Verifying,ProjectState.Finalising)
fun projectTransitionAllowed(from: ProjectState,to: ProjectState): Boolean = from==to || when {
    from==ProjectState.Completed -> false
    from==ProjectState.Finalising -> to in setOf(ProjectState.Completed,ProjectState.Failed,ProjectState.Interrupted)
    to in setOf(ProjectState.Failed,ProjectState.Cancelled,ProjectState.Interrupted) -> true
    from in setOf(ProjectState.WaitingDestination,ProjectState.Failed,ProjectState.Cancelled,ProjectState.Interrupted) -> to==ProjectState.Preparing
    from==ProjectState.Preparing -> to==ProjectState.Writing
    from==ProjectState.Writing -> to==ProjectState.Verifying
    from==ProjectState.Verifying -> to==ProjectState.Finalising
    else -> false
}
