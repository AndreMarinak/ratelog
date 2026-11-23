package org.ratelog.user.edit

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import org.ratelog.Email
import org.ratelog.Password
import org.ratelog.Role
import org.ratelog.Username
import org.ratelog.user.User
import org.ratelog.user.UserRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

data class EditUserCommand(
    val currentUser: User,
    val targetUserId: User.Id,
    val newUsername: Username,
    val newEmail: Email,
    val currentPassword: Password?,
    val newPassword: Password?,
    val newRole: Role?,
)

@Component
class EditUserHandler(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    @Transactional
    fun handle(command: EditUserCommand): Either<EditUserHandlerError, User> = either {
        val isSelfEdit = command.currentUser.id == command.targetUserId
        ensure(isSelfEdit || command.currentUser.role.isAdminLike) { EditUserHandlerError.Forbidden }

        val target = userRepository.findById(command.targetUserId)
            ?: raise(EditUserHandlerError.UserNotFound)

        if (!isSelfEdit && command.currentUser.role == Role.ADMIN) {
            ensure(target.role in ADMIN_MANAGED_ROLES) { EditUserHandlerError.CannotEditAdmin }
        }

        val actingUser = (if (isSelfEdit) target else command.currentUser.id?.let { userRepository.findById(it) })
            ?: raise(EditUserHandlerError.Forbidden)

        // Fork: your own account always needs your current password; only a superadmin editing someone else skips it.
        if (isSelfEdit || command.currentUser.role != Role.SUPERADMIN) {
            ensure(command.currentPassword != null) { EditUserHandlerError.MissingCurrentPassword }
        }

        command.currentPassword?.let { currentPassword ->
            ensure(passwordEncoder.matches(currentPassword.value, actingUser.passwordHash)) {
                EditUserHandlerError.InvalidCurrentPassword
            }
        }

        val existingByUsername = userRepository.findByUsername(command.newUsername)
        ensure(existingByUsername == null || existingByUsername.id == command.targetUserId) {
            EditUserHandlerError.UsernameAlreadyExists
        }

        val existingByEmail = userRepository.findByEmail(command.newEmail)
        ensure(existingByEmail == null || existingByEmail.id == command.targetUserId) {
            EditUserHandlerError.EmailAlreadyExists
        }

        val newRole = command.newRole ?: target.role

        if (newRole != target.role) {
            ensure(command.currentUser.role.isAdminLike) { EditUserHandlerError.CannotChangeRole }
            ensure(newRole != Role.SUPERADMIN) { EditUserHandlerError.CannotPromoteToSuperadmin }
            ensure(target.role != Role.SUPERADMIN) { EditUserHandlerError.CannotChangeSuperadminRole }
            ensure(!isSelfEdit) { EditUserHandlerError.CannotDemoteYourself }
            // Fork: an ADMIN may give or take away MANAGER (USER <-> MANAGER); other role changes stay SUPERADMIN-only.
            if (command.currentUser.role != Role.SUPERADMIN) {
                ensure(target.role in ADMIN_MANAGED_ROLES && newRole in ADMIN_MANAGED_ROLES) { EditUserHandlerError.CannotChangeRole }
            }
        }

        val newHash = command.newPassword?.value?.let(passwordEncoder::encode) ?: target.passwordHash

        userRepository.updateCredentials(command.targetUserId, command.newUsername, command.newEmail, newHash)
        if (newRole != target.role) {
            userRepository.updateRole(command.targetUserId, newRole)
        }

        userRepository.findById(command.targetUserId)!!
    }

    companion object {
        /** Fork: the accounts an ADMIN may edit, and the roles an ADMIN may switch them between. */
        val ADMIN_MANAGED_ROLES = setOf(Role.USER, Role.MANAGER)
    }
}
