This is an app that controls my room’s Hue lights using Android’s ControlsProviderService. ControlsProviderService lets you make widgets you can access without passing the device lockscreen.

Hue Lockscreen is meant to demonstrate Android’s underused ControlsProviderService API, but, if you want to use this project, too, you need to put two properties in the root project’s local.properties before building:
<br/>address="[local ip address of the Hue Hub]"
<br/>access="[access token]"

License
--------

    Copyright 2023 Eric Cochran

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
