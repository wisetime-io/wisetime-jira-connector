<#if getDescription()?has_content>${getDescription()}</#if>

<#if getNarrativeType() == "NARRATIVE_AND_TIME_ROW_ACTIVITY_DESCRIPTIONS">
 <#list getTimeRows() as timeRow>
  |${timeRow.getActivity()}|${timeRow.getDescription()!"N/A"}|
 </#list>
</#if>

Total worked time: ${getTotalDuration(getTimeRows())?string.@duration}
Total chargeable time: ${getTotalDurationSecs()?string.@duration}
Experience factor: ${getUser().getExperienceWeightingPercent()}%

<#if getDurationSplitStrategy() == "DIVIDE_BETWEEN_TAGS" && (getTags()?size > 1)>
The above times have been split across ${getTags()?size} cases and are thus greater than the chargeable time in this case
</#if>

<#function getTotalDuration timeRows>
 <#local rowTotalDuration = 0?number>
 <#list timeRows as timeRow>
  <#local rowTotalDuration += timeRow.getDurationSecs()>
 </#list>
 <#return rowTotalDuration />
</#function>


