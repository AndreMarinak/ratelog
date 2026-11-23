package org.ratelog.customlist.official

import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/*
 * "Official implies public" on every save path (upstream's edit form, the API, any future code) without editing
 * upstream's UpdateListHandler: saving an official list as private keeps it public. To make it private, un-mark it
 * first (admins). Same decorator pattern as watchlist/autowatched.
 */
class OfficialListsStayPublicRepository(
    private val delegate: CustomListRepository,
    private val officialLists: OfficialListRepository,
) : CustomListRepository by delegate {

    override fun save(list: CustomList): CustomList {
        val id = list.id
        if (id != null && !list.isPublic && officialLists.isOfficial(id)) {
            return delegate.save(list.copy(isPublic = true))
        }
        return delegate.save(list)
    }
}

/** Makes every `CustomListRepository` injection point get the decorated repository. */
@Configuration
class OfficialListsConfig {
    @Bean
    @Primary
    fun officialListsStayPublicRepository(
        @Qualifier("customListRepositoryImpl") delegate: CustomListRepository,
        officialLists: OfficialListRepository,
    ): CustomListRepository = OfficialListsStayPublicRepository(delegate, officialLists)
}
